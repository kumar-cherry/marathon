package mesosphere.marathon
package state

import java.util.Objects

import com.typesafe.scalalogging.StrictLogging
import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.util.summarize
import mesosphere.marathon.api.v2.Validation._
import mesosphere.marathon.api.v2.validation.AppValidation
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.plugin.{Group => IGroup}
import mesosphere.marathon.state.Group.{defaultApps, defaultDependencies, defaultGroups, defaultPods, defaultVersion}
import mesosphere.marathon.state.PathId.{StringPathId, validPathWithBase}

class Group(
    val id: PathId,
    val apps: Map[AppDefinition.AppKey, AppDefinition] = defaultApps,
    val pods: Map[PathId, PodDefinition] = defaultPods,
    val groupsById: Map[Group.GroupKey, Group] = defaultGroups,
    val dependencies: Set[PathId] = defaultDependencies,
    val version: Timestamp = defaultVersion) extends IGroup {

  /**
    * Get app from this group or any child group.
    *
    * @param appId The app to retrieve.
    * @return None if the app was not found or non empty option with app.
    */
  def app(appId: PathId): Option[AppDefinition] = {
    apps.get(appId) orElse group(appId.parent).flatMap(_.apps.get(appId))
  }

  /**
    * Get pod from this group or any child group.
    *
    * @param podId The pod to retrieve.
    * @return None if the pod was not found or non empty option with pod.
    */
  def pod(podId: PathId): Option[PodDefinition] = {
    pods.get(podId) orElse group(podId.parent).flatMap(_.pods.get(podId))
  }

  /**
    * Get a runnable specification from this group or any child group.
    *
    * @param id The path of the run spec to retrieve.
    * @return None of run spec was not found or non empty option with run spec.
    */
  def runSpec(id: PathId): Option[RunSpec] = {
    val maybeApp = this.app(id)
    if (maybeApp.isDefined) maybeApp else this.pod(id)
  }

  /**
    * Checks whether a runnable spec with the given id exists.
    *
    * @param id Id of an app or pod.
    * @return True if app or pod exists, false otherwise.
    */
  def exists(id: PathId): Boolean = runSpec(id).isDefined

  /**
    * Find and return the child group for the given path.
    *
    * @param gid The path of the group for find.
    * @return None if no group was found or non empty option with group.
    */
  def group(gid: PathId): Option[Group] = transitiveGroupsById.get(gid)

  private def transitiveAppsIterator(): Iterator[AppDefinition] = apps.valuesIterator ++ groupsById.valuesIterator.flatMap(_.transitiveAppsIterator())
  private def transitiveAppIdsIterator(): Iterator[PathId] = apps.keysIterator ++ groupsById.valuesIterator.flatMap(_.transitiveAppIdsIterator())
  lazy val transitiveApps: Iterable[AppDefinition] = transitiveAppsIterator().toVector
  lazy val transitiveAppIds: Iterable[PathId] = transitiveAppIdsIterator().toVector

  private def transitivePodsIterator(): Iterator[PodDefinition] = pods.valuesIterator ++ groupsById.valuesIterator.flatMap(_.transitivePodsIterator())
  private def transitivePodIdsIterator(): Iterator[PathId] = pods.keysIterator ++ groupsById.valuesIterator.flatMap(_.transitivePodIdsIterator())
  lazy val transitivePods: Iterable[PodDefinition] = transitivePodsIterator().toVector
  lazy val transitivePodIds: Iterable[PathId] = transitivePodIdsIterator().toVector

  lazy val transitiveRunSpecs: Iterable[RunSpec] = transitiveApps ++ transitivePods
  lazy val transitiveRunSpecIds: Iterable[PathId] = transitiveAppIds ++ transitivePodIds

  def transitiveGroups(): Iterator[(Group.GroupKey, Group)] = groupsById.iterator ++ groupsById.valuesIterator.flatMap(_.transitiveGroups())
  lazy val transitiveGroupsById: Map[Group.GroupKey, Group] = {
    val builder = Map.newBuilder[Group.GroupKey, Group]
    builder += id -> this
    builder ++= transitiveGroups()
    builder.result()
  }
  lazy val transitiveGroupValues: Iterable[Group] = transitiveGroups().map(_._2).toVector

  /** @return true if and only if this group directly or indirectly contains app definitions. */
  def containsApps: Boolean = apps.nonEmpty || groupsById.exists { case (_, group) => group.containsApps }

  def containsPods: Boolean = pods.nonEmpty || groupsById.exists { case (_, group) => group.containsPods }

  def containsAppsOrPodsOrGroups: Boolean = apps.nonEmpty || groupsById.nonEmpty || pods.nonEmpty

  override def equals(other: Any): Boolean = other match {
    case that: Group =>
      id == that.id &&
        apps == that.apps &&
        pods == that.pods &&
        groupsById == that.groupsById &&
        dependencies == that.dependencies &&
        version == that.version
    case _ => false
  }

  override def hashCode(): Int = Objects.hash(id, apps, pods, groupsById, dependencies, version)

  override def toString = {
    val summarizedApps = summarize(apps.valuesIterator.map(_.id))
    val summarizedPods = summarize(pods.valuesIterator.map(_.id))
    val summarizedGroups = summarize(groupsById.valuesIterator.map(_.id))
    val summarizedDependencies = summarize(dependencies.iterator)

    s"Group($id, apps = $summarizedApps, pods = $summarizedPods, groups = $summarizedGroups, dependencies = $summarizedDependencies, version = $version)"
  }
}

object Group extends StrictLogging {
  type GroupKey = PathId

  def apply(
    id: PathId,
    apps: Map[AppDefinition.AppKey, AppDefinition] = Group.defaultApps,
    pods: Map[PathId, PodDefinition] = Group.defaultPods,
    groupsById: Map[Group.GroupKey, Group] = Group.defaultGroups,
    dependencies: Set[PathId] = Group.defaultDependencies,
    version: Timestamp = Group.defaultVersion): Group =
    new Group(id, apps, pods, groupsById, dependencies, version)

  def empty(id: PathId): Group =
    Group(id = id, version = Timestamp(0))

  def defaultApps: Map[AppDefinition.AppKey, AppDefinition] = Map.empty
  val defaultPods = Map.empty[PathId, PodDefinition]
  def defaultGroups: Map[Group.GroupKey, Group] = Map.empty
  def defaultDependencies: Set[PathId] = Set.empty
  def defaultVersion: Timestamp = Timestamp.now()

  def validGroup(base: PathId, enabledFeatures: Set[String]): Validator[Group] =
    validator[Group] { group =>
      group.id is validPathWithBase(base)

      group.transitiveApps as "apps" is everyApp(
        AppDefinition.validBasicAppDefinition(enabledFeatures) and isChildOfParentId(group)
      )

      group is noAppsAndPodsWithSameId
      group is noAppsAndGroupsWithSameName
      group is noPodsAndGroupsWithSameName

      group.transitiveGroupValues as "groups" is every(
        noAppsAndPodsWithSameId and noAppsAndGroupsWithSameName and noPodsAndGroupsWithSameName
          and isTrue("Group has to be child of groups with parent id") { childGroup =>
            if (childGroup.id.parent == group.id) group.groupsById.contains(childGroup.id)
            else {
              group.group(childGroup.id.parent).exists(parent => parent.groupsById.contains(childGroup.id))
            }
          }
      )
    }

  import scala.language.implicitConversions

  implicit def everyApp(validator: Validator[AppDefinition]): Validator[Iterable[AppDefinition]] = {
    new Validator[Iterable[AppDefinition]] {
      override def apply(seq: Iterable[AppDefinition]): Result = {
        seq.foldLeft[Result](Success) {
          case (accum, (app)) =>
            validator(app) match {
              case Success => accum
              case Failure(violations) =>
                val scopedViolations = violations.map { violation =>
                  violation.withPath(Descriptions.Explicit(app.id.toString))
                }
                accum.and(Failure(scopedViolations))
            }
        }
      }
    }
  }

  /**
    * An app validator to verify that the app is in a proper child group of group.
    *
    * @param group The parent group for all sub groups to check.
    * @return The validator.
    */
  private def isChildOfParentId(group: Group): Validator[AppDefinition] = {
    isTrue("App has to be child of group with parent id") { app =>
      if (app.id.parent == group.id) group.apps.contains(app.id)
      else {
        group.group(app.id.parent).exists(child => child.apps.contains(app.id))
      }
    }
  }

  private def noAppsAndPodsWithSameId: Validator[Group] =
    isTrue("Applications and Pods may not share the same id") { group =>
      !group.transitiveAppIds.exists(appId => group.pod(appId).isDefined)
    }

  private def noAppsAndGroupsWithSameName: Validator[Group] =
    isTrue("Groups and Applications may not have the same identifier.") { group =>
      val childGroupIds = group.groupsById.keys
      val clashingIds = childGroupIds.filter(group.apps.contains(_))
      if (clashingIds.nonEmpty)
        logger.info(s"Found the following clashingIds in group ${group.id}: ${clashingIds}")
      clashingIds.isEmpty
    }

  private def noPodsAndGroupsWithSameName: Validator[Group] =
    isTrue("Groups and Pods may not have the same identifier.") { group =>
      val childGroupIds = group.groupsById.keys
      val clashingIds = childGroupIds.filter(group.pods.contains(_))
      if (clashingIds.nonEmpty)
        logger.info(s"Found the following clashingIds in group ${group.id}: ${clashingIds}")
      clashingIds.isEmpty
    }

  def emptyUpdate(id: PathId): raml.GroupUpdate = raml.GroupUpdate(Some(id.toString))

  /** requires that apps are in canonical form */
  def validNestedGroupUpdateWithBase(base: PathId): Validator[raml.GroupUpdate] =
    validator[raml.GroupUpdate] { group =>
      group is notNull

      group.version is theOnlyDefinedOptionIn(group)
      group.scaleBy is theOnlyDefinedOptionIn(group)

      // this is funny: id is "optional" only because version and scaleBy can't be used in conjunction with other
      // fields. it feels like we should make an exception for "id" and always require it for non-root groups.
      group.id.map(_.toPath) as "id" is optional(valid)

      group.apps is optional(every(
        AppValidation.validNestedApp(group.id.fold(base)(PathId(_).canonicalPath(base)))))
      group.groups is optional(every(
        validNestedGroupUpdateWithBase(group.id.fold(base)(PathId(_).canonicalPath(base)))))
    }
}
