/*
 * This Kotlin source file was generated by the Gradle 'init' task.
 */
package io.github.gmazzo.publications.report

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.flow.FlowProviders
import org.gradle.api.flow.FlowScope
import org.gradle.api.invocation.Gradle
import org.gradle.api.provider.MapProperty
import org.gradle.api.publish.maven.MavenArtifact
import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven
import org.gradle.api.publish.maven.tasks.PublishToMavenLocal
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.build.event.BuildEventsListenerRegistry
import org.gradle.kotlin.dsl.always
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.mapProperty
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.registerIfAbsent
import org.gradle.kotlin.dsl.withType
import javax.inject.Inject

class ReportPublicationsPlugin @Inject constructor(
    private val buildEventsListenerRegistry: BuildEventsListenerRegistry,
    private val flowScope: FlowScope,
    private val flowProviders: FlowProviders,
) : Plugin<Project> {

    override fun apply(project: Project): Unit = with(project) {
        if (project != rootProject) {
            rootProject.apply<ReportPublicationsPlugin>()
            return
        }

        val service = gradle.sharedServices.registerIfAbsent("publicationsReport", ReportPublicationsService::class) {}
        val publications = gradle.rootBuild().createMapProperty("publicationsReport")

        buildEventsListenerRegistry.onTaskCompletion(service)

        if (gradle.parent == null) {
            flowScope.always(ReportPublicationsFlowAction::class) {
                parameters.publications.set(flowProviders.buildWorkResult.zip(publications) { _, publications ->
                    publications.mapValues { (_, it) -> Json.decodeFromString<ReportPublication>(it) }
                })
                parameters.outcomes.set(flowProviders.buildWorkResult.zip(service) { _, it -> it.tasksOutcome })
            }
        }

        allprojects {
            val buildPath = gradle.path

            tasks.withType<AbstractPublishToMaven>().configureEach {
                publications.putAll(tasks.named<AbstractPublishToMaven>(this@configureEach.name)
                    .map { mapOf( buildPath + it.path to Json.encodeToString(resolvePublication(it))) })
            }
        }
    }

    private fun resolvePublication(task: AbstractPublishToMaven): ReportPublication {
        val repository = when (task) {
            is PublishToMavenLocal -> ReportPublication.Repository(
                name = "mavenLocal",
                value = "~/.m2/repository"
            )

            is PublishToMavenRepository -> ReportPublication.Repository(
                name = task.repository.name,
                task.repository.url.toString()
            )

            else -> ReportPublication.Repository(name = "<unknown>", value = "")
        }
        val artifacts = task.publication.artifacts.sortedWith(compareBy(MavenArtifact::getClassifier)).map {
            when (val classifier = it.classifier) {
                null -> it.extension
                else -> "$classifier.${it.extension}"
            }
        }.ifEmpty { listOf("pom") }

        return ReportPublication(
            groupId = task.publication.groupId,
            artifactId = task.publication.artifactId,
            version = task.publication.version,
            repository = repository,
            outcome = ReportPublication.Outcome.NotRun,
            artifacts = artifacts
        )
    }

    private tailrec fun Gradle.rootBuild(): Gradle = when (val parent = parent) {
        null -> this
        else -> parent.rootBuild()
    }

    @Suppress("RecursivePropertyAccessor")
    private val Gradle.path: String
        get() = when (val parent = parent) {
            null -> ""
            else -> "${parent.path}:${rootProject.name}"
        }

    @Suppress("UNCHECKED_CAST")
    private fun Gradle.createMapProperty(name: String) = with(extensions) {
        findByName(name) as MapProperty<String, String>?
            ?: rootProject.objects.mapProperty<String, String>().also { add(name, it) }
    }

}
