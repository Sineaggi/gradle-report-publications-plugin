package io.github.gmazzo.publications.report

import io.github.gmazzo.publications.report.ReportPublicationSerializer.deserialize
import io.github.gmazzo.publications.report.ReportPublicationSerializer.serialize
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.flow.FlowScope
import org.gradle.api.internal.GradleInternal
import org.gradle.api.invocation.Gradle
import org.gradle.api.logging.configuration.ShowStacktrace
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Provider
import org.gradle.api.publish.maven.MavenArtifact
import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven
import org.gradle.api.publish.maven.tasks.PublishToMavenLocal
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.build.event.BuildEventsListenerRegistry
import org.gradle.kotlin.dsl.always
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.mapProperty
import org.gradle.kotlin.dsl.registerIfAbsent
import org.gradle.util.GradleVersion
import javax.inject.Inject

class ReportPublicationsPlugin @Inject constructor(
    private val buildEventsListenerRegistry: BuildEventsListenerRegistry,
    private val flowScope: FlowScope,
) : Plugin<Project> {

    companion object {
        const val MIN_GRADLE_VERSION = "8.1"
    }

    override fun apply(project: Project): Unit = with(project) {
        check(GradleVersion.current() >= GradleVersion.version(MIN_GRADLE_VERSION)) {
            "Gradle version must be at least $MIN_GRADLE_VERSION"
        }

        if (project != rootProject) {
            rootProject.apply<ReportPublicationsPlugin>()
            return
        }

        val publications = createPublicationsCollector()
        val service = createCollectTaskOutcomeService()

        collectPublishTasksPublications(publications)

        if (gradle.parent == null) { // we only report at the root main build
            registerPublicationsReporter(publications, service)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun Project.createPublicationsCollector() = with(gradle.rootBuild().extensions) {
        findByName("publicationsReport") as MapProperty<String, ByteArray>?
            ?: rootProject.objects.mapProperty<String, ByteArray>().also { add("publicationsReport", it) }
    }

    private fun Project.createCollectTaskOutcomeService() = gradle.sharedServices
        .registerIfAbsent("publicationsReport", ReportPublicationsService::class) {}
        .also(buildEventsListenerRegistry::onTaskCompletion)

    private fun Project.collectPublishTasksPublications(publications: MapProperty<String, ByteArray>) {
        val buildPath = gradle.path

        gradle.taskGraph.whenReady {
            publications.putAll(provider {
                allTasks.asSequence()
                    .filterIsInstance<AbstractPublishToMaven>()
                    .mapNotNull {
                        runCatching { buildPath + it.path to serialize(resolvePublication(it)) }
                            .onFailure { ex ->
                                logger.warn(
                                    "Failed to resolve publication for task ${it.path}",
                                    ex.takeIf { gradle.startParameter.showStacktrace == ShowStacktrace.ALWAYS })
                            }
                            .getOrNull()
                    }
                    .toMap()
            })
        }
    }

    private fun Project.resolvePublication(task: AbstractPublishToMaven): ReportPublication {
        val repository = when (task) {
            is PublishToMavenLocal -> ReportPublication.Repository(
                name = "mavenLocal",
                value = "~/.m2/repository"
            )

            is PublishToMavenRepository -> ReportPublication.Repository(
                name = task.repository.name,
                value = task.repository.url.toString()
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
            outcome = if (gradle.startParameter.isDryRun) ReportPublication.Outcome.Skipped else ReportPublication.Outcome.Unknown,
            artifacts = artifacts
        )
    }

    private fun registerPublicationsReporter(
        publications: MapProperty<String, ByteArray>,
        service: Provider<ReportPublicationsService>,
    ) {
        flowScope.always(ReportPublicationsFlowAction::class) {
            parameters.publications.set(publications.map { it.mapValues { (_, pub) -> deserialize(pub) } })
            parameters.outcomes.set(service.map { it.tasksOutcome })
        }
    }

    private tailrec fun Gradle.rootBuild(): Gradle = when (val parent = parent) {
        null -> this
        else -> parent.rootBuild()
    }

    @Suppress("RecursivePropertyAccessor")
    private val Gradle.path: String
        get() = when (val parent = parent) {
            null -> ""
            else -> "${parent.path}${(this as GradleInternal).identityPath}"
        }

}
