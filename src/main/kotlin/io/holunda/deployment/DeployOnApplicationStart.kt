package io.holunda.deployment

import io.github.oshai.kotlinlogging.KotlinLogging
import org.camunda.bpm.engine.RepositoryService
import org.camunda.bpm.engine.repository.DeploymentBuilder
import org.camunda.bpm.spring.boot.starter.event.PostDeployEvent
import org.camunda.bpm.spring.boot.starter.property.CamundaBpmProperties
import org.springframework.context.event.EventListener
import org.springframework.core.io.Resource
import org.springframework.core.io.ResourceLoader
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.util.StopWatch

private val logger = KotlinLogging.logger {}

open class DeployOnApplicationStart(
  private val camundaDeployment: CamundaDeploymentProperties,
  private val repositoryService: RepositoryService,
  private val resourceLoader: ResourceLoader,
) {

  companion object {
    const val PROCESS_APPLICATION = "process application"
    val CAMUNDA_FILE_SUFFIXES: Set<String> = buildSet {
      addAll(CamundaBpmProperties.DEFAULT_BPMN_RESOURCE_SUFFIXES)
      addAll(CamundaBpmProperties.DEFAULT_CMMN_RESOURCE_SUFFIXES)
      addAll(CamundaBpmProperties.DEFAULT_DMN_RESOURCE_SUFFIXES)
    }
  }

  @EventListener
  fun accept(event: PostDeployEvent) {
    logger.info { "Starting Camunda deployment" }

    if (camundaDeployment.archives.isEmpty()) {
      logger.warn { "No process archives specified" }
    }

    camundaDeployment.archives.forEach {
      val otherPaths = if (camundaDeployment.allowOverlapping) {
        (camundaDeployment.archives
          .map { archive -> archive.path }
          .filterNot { otherPath -> it.path.startsWith(otherPath) } // don't take parent paths if current is contained in it
          - it.path // remove yourself
          ).toSet()
      } else {
        emptySet()
      }
      deployProcessArchive(it, otherPaths)
    }

    logger.info { "Camunda deployment finished" }
  }

  private fun deployProcessArchive(processArchive: CamundaDeploymentProperties.ProcessArchive, otherPaths: Set<String>) {
    logger.info { "Deploying process archive: [$processArchive]" }

    val resources = PathMatchingResourcePatternResolver().getResources("classpath*:${processArchive.path}/**/*.*")
      .filter { isCamundaResource(it) }
      .filter { isNotPartOfOtherProcessArchivePaths(it, otherPaths) }

    if (resources.isNotEmpty()) {
      val deploymentBuilder = repositoryService.createDeployment()
        .name(processArchive.name)
        .source(PROCESS_APPLICATION)
        .enableDuplicateFiltering(false)
        .tenantId(processArchive.tenant)

      resources
        .forEach { addDeployment(processArchive, deploymentBuilder, it) }
      val stopWatch = StopWatch()

      stopWatch.start()
      deploymentBuilder.deploy()
      stopWatch.stop()

      logger.info { "Deployment of ${deploymentBuilder.resourceNames.size} resources took ${stopWatch.totalTimeSeconds} seconds" }
    } else {
      logger.warn { "No resources found in the specified path: [${processArchive.path}]" }
    }
  }

  private fun isNotPartOfOtherProcessArchivePaths(resource: Resource, otherPaths: Set<String>): Boolean =
    otherPaths.none { path -> resource.uri.toString().contains(path) }

  private fun isCamundaResource(resource: Resource) = CAMUNDA_FILE_SUFFIXES.contains(resource.filename!!.substringAfterLast("."))

  private fun addDeployment(
    processArchive: CamundaDeploymentProperties.ProcessArchive,
    deploymentBuilder: DeploymentBuilder,
    resource: Resource
  ) = sanitizePath(resource.uri.toString(), processArchive.path)
    .also { logger.info { "Adding resource: [$it]" } }
    .let {
      deploymentBuilder.addInputStream(resource.filename, resourceLoader.getResource("classpath:$it").inputStream)
    }

  private fun sanitizePath(path: String, fragment: String) =
    if (fragment.isEmpty()) {
      path.substring(path.lastIndexOf("/"))
    } else {
      path.substring(path.indexOf(fragment))
    }
}
