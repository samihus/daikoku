package fr.maif.otoroshi.daikoku.utils

import akka.http.scaladsl.util.FastFuture
import cats.data.EitherT
import controllers.AppError
import controllers.AppError._
import fr.maif.otoroshi.daikoku.domain.TeamPermission.Administrator
import fr.maif.otoroshi.daikoku.domain.UsagePlan._
import fr.maif.otoroshi.daikoku.domain._
import fr.maif.otoroshi.daikoku.env.Env
import fr.maif.otoroshi.daikoku.utils.StringImplicits._
import org.joda.time.DateTime
import play.api.i18n.{Lang, MessagesApi}
import play.api.libs.json.{JsArray, JsError, JsNull, JsObject, Json}
import reactivemongo.bson.BSONObjectID

import scala.concurrent.Future

class ApiService(env: Env, otoroshiClient: OtoroshiClient, messagesApi: MessagesApi) {

  implicit val ec = env.defaultExecutionContext
  implicit val ev = env

  def subscribeToApi(tenant: Tenant,
                     user: User,
                     api: Api,
                     planId: String,
                     team: Team,
                     customMetadata: Option[JsObject] = None,
                     customMaxPerSecond: Option[Long] = None,
                     customMaxPerDay: Option[Long] = None,
                     customMaxPerMonth: Option[Long] = None,
                     customReadOnly: Option[Boolean] = None): Future[Either[AppError, JsObject]] = {
    val defaultPlanOpt =
      api.possibleUsagePlans.find(p => p.id == api.defaultUsagePlan)
    val askedUsagePlan = api.possibleUsagePlans.find(p => p.id.value == planId)
    val plan: UsagePlan = askedUsagePlan
      .orElse(defaultPlanOpt)
      .getOrElse(api.possibleUsagePlans.head)

    def createKey(api: Api, plan: UsagePlan, team: Team, group: JsObject)(
      implicit otoroshiSettings: OtoroshiSettings
    ): Future[Either[AppError, JsObject]] = {
      import cats.implicits._
      // TODO: verify if group is in authorized groups (if some)
      val groupId = (group \ "id").as[String]
      val createdAt = DateTime.now().toString()
      val clientId = IdGenerator.token(32)
      val clientSecret = IdGenerator.token(64)
      val clientName =
        s"daikoku-api-key-${api.humanReadableId}-${
          plan.customName
            .getOrElse(plan.typeName)
            .urlPathSegmentSanitized
        }-${team.humanReadableId}-${System.currentTimeMillis()}"
      val integrationToken = IdGenerator.token(64)
      val apiSubscription = ApiSubscription(
        id = ApiSubscriptionId(BSONObjectID.generate().stringify),
        tenant = tenant.id,
        apiKey = OtoroshiApiKey(clientName, clientId, clientSecret),
        plan = plan.id,
        createdAt = DateTime.now(),
        team = team.id,
        api = api.id,
        by = user.id,
        customName = None,
        rotation = plan.autoRotation.map(rotation => ApiSubscriptionRotation(enabled = rotation)),
        integrationToken = integrationToken,
        customMetadata = customMetadata,
        customMaxPerSecond = customMaxPerSecond,
        customMaxPerDay = customMaxPerDay,
        customMaxPerMonth = customMaxPerMonth,
        customReadOnly = customReadOnly
      )
      val ctx = Map(
        "user.id" -> user.id.value,
        "user.name" -> user.name,
        "user.email" -> user.email,
        "api.id" -> api.id.value,
        "api.name" -> api.name,
        "team.id" -> team.id.value,
        "team.name" -> team.name,
        "tenant.id" -> tenant.id.value,
        "tenant.name" -> tenant.name,
        "createdAt" -> createdAt,
        "client.id" -> clientId,
        "client.name" -> clientName,
        "group.id" -> groupId
      ) ++ team.metadata.map(t => ("team.metadata." + t._1, t._2)) ++ user.metadata
        .map(
          t => ("user.metadata." + t._1, t._2)
        )
      val apiKey = ActualOtoroshiApiKey(
        clientId = clientId,
        clientSecret = clientSecret,
        clientName = clientName,
        authorizedGroup = groupId,
        throttlingQuota = 1000,
        dailyQuota = RemainingQuotas.MaxValue,
        monthlyQuota = RemainingQuotas.MaxValue,
        allowClientIdOnly =
          plan.otoroshiTarget.exists(_.apikeyCustomization.clientIdOnly),
        readOnly = customReadOnly.getOrElse(plan.otoroshiTarget.exists(_.apikeyCustomization.readOnly)),
        constrainedServicesOnly = plan.otoroshiTarget.exists(
          _.apikeyCustomization.constrainedServicesOnly),
        tags = plan.otoroshiTarget
          .map(_.processedTags(ctx))
          .getOrElse(Seq.empty[String]),
        restrictions = plan.otoroshiTarget
          .map(_.apikeyCustomization.restrictions)
          .getOrElse(ApiKeyRestrictions()),
        metadata = Map(
          "daikoku_created_by" -> user.email,
          "daikoku_created_from" -> "daikoku",
          "daikoku_created_at" -> createdAt,
          "daikoku_created_with_id" -> api.id.value,
          "daikoku_created_with" -> api.name,
          "daikoku_created_for_team_id" -> team.id.value,
          "daikoku_created_for_team" -> team.name,
          "daikoku_created_on_tenant" -> tenant.id.value,
          "daikoku_integration_token" -> integrationToken
        ) ++ plan.otoroshiTarget
          .map(_.processedMetadata(ctx))
          .getOrElse(Map.empty[String, String])
          ++ customMetadata
          .flatMap(_.asOpt[Map[String, String]])
          .getOrElse(Map.empty[String, String]),
        rotation = plan.autoRotation.map(enabled => ApiKeyRotation(enabled = enabled))
      )
      val tunedApiKey = plan match {
        case _: FreeWithoutQuotas => apiKey
        case p: FreeWithQuotas =>
          apiKey.copy(
            throttlingQuota = customMaxPerSecond.getOrElse(p.maxPerSecond),
            dailyQuota = customMaxPerDay.getOrElse(p.maxPerDay),
            monthlyQuota = customMaxPerMonth.getOrElse(p.maxPerMonth))
        case p: QuotasWithLimits =>
          apiKey.copy(
            throttlingQuota = customMaxPerSecond.getOrElse(p.maxPerSecond),
            dailyQuota = customMaxPerDay.getOrElse(p.maxPerDay),
            monthlyQuota = customMaxPerMonth.getOrElse(p.maxPerMonth))
        case p: QuotasWithoutLimits =>
          apiKey.copy(
            throttlingQuota = customMaxPerSecond.getOrElse(p.maxPerSecond),
            dailyQuota = customMaxPerDay.getOrElse(p.maxPerDay),
            monthlyQuota = customMaxPerMonth.getOrElse(p.maxPerMonth))
        case _: PayPerUse => apiKey
        case _: Admin => apiKey
      }
      val r: EitherT[Future, AppError, JsObject] = for {
        _ <- EitherT(otoroshiClient.createApiKey(groupId, tunedApiKey))
        _ <- EitherT.liftF(
          env.dataStore.apiSubscriptionRepo
            .forTenant(tenant.id)
            .save(apiSubscription))
        _ <- EitherT.liftF(
          env.dataStore.teamRepo
            .forTenant(tenant.id)
            .save(team.copy(
              subscriptions = team.subscriptions :+ apiSubscription.id))
        )
        _ <- EitherT.liftF(
          env.dataStore.apiRepo
            .forTenant(tenant.id)
            .save(
              api.copy(subscriptions = api.subscriptions :+ apiSubscription.id))
        )
      } yield {
        Json.obj("creation" -> "done", "subscription" -> apiSubscription.asJson)
      }

      r.value
    }

    def createAdminKey(api: Api, plan: UsagePlan): Future[Either[AppError, JsObject]] = {
      import cats.implicits._
      // TODO: verify if group is in authorized groups (if some)

      val clientId = IdGenerator.token(32)
      val clientSecret = IdGenerator.token(64)
      val clientName =
        s"daikoku-api-key-${api.humanReadableId}-${
          plan.customName
            .getOrElse(plan.typeName)
            .urlPathSegmentSanitized
        }-${team.humanReadableId}-${System.currentTimeMillis()}"
      val apiSubscription = ApiSubscription(
        id = ApiSubscriptionId(BSONObjectID.generate().stringify),
        tenant = tenant.id,
        apiKey = OtoroshiApiKey(clientName, clientId, clientSecret),
        plan = plan.id,
        createdAt = DateTime.now(),
        team = team.id,
        api = api.id,
        by = user.id,
        customName = None,
        rotation = plan.autoRotation.map(_ => ApiSubscriptionRotation()),
        integrationToken = IdGenerator.token(64)
      )

      val r: EitherT[Future, AppError, JsObject] = for {
        _ <- EitherT.liftF(
          env.dataStore.apiSubscriptionRepo
            .forTenant(tenant.id)
            .save(apiSubscription))
        _ <- EitherT.liftF(
          env.dataStore.teamRepo
            .forTenant(tenant.id)
            .save(team.copy(
              subscriptions = team.subscriptions :+ apiSubscription.id))
        )
        _ <- EitherT.liftF(
          env.dataStore.apiRepo
            .forTenant(tenant.id)
            .save(
              api.copy(subscriptions = api.subscriptions :+ apiSubscription.id))
        )
        _ <- EitherT.liftF(
          env.dataStore.tenantRepo.save(tenant.copy(adminSubscriptions = tenant.adminSubscriptions :+ apiSubscription.id))
        )
      } yield {
        Json.obj("creation" -> "done", "subscription" -> apiSubscription.asJson)
      }

      r.value
    }

    plan.otoroshiTarget.map(_.otoroshiSettings).flatMap { id =>
      tenant.otoroshiSettings.find(_.id == id)
    } match {
      case None if api.visibility == ApiVisibility.AdminOnly => createAdminKey(api, plan)
      case None => Future.successful(Left(OtoroshiSettingsNotFound))
      case Some(otoSettings) =>
        implicit val otoroshiSettings: OtoroshiSettings = otoSettings
        plan.otoroshiTarget match {
          case None => Future.successful(Left(ApiNotLinked))
          case Some(target) =>
            val customMetadataKeys = target.apikeyCustomization.customMetadata.map(_.key)
            val isCustomMetadataProvided =
              customMetadataKeys.intersect(customMetadata.map(_.keys.toSeq).getOrElse(Seq.empty)) == customMetadataKeys &&
                customMetadata.map(_.values.toSeq).forall(values => !values.contains(JsNull))

            if (isCustomMetadataProvided) {
              otoroshiClient
                .getServiceGroup(target.serviceGroup.value)
                .flatMap(group => createKey(api, plan, team, group))
            } else {
              FastFuture.successful(Left(ApiKeyCustomMetadataNotPrivided))
            }
        }
    }
  }

  def updateSubscription(tenant: Tenant,
                         subscription: ApiSubscription,
                         api: Api): Future[Either[AppError, JsObject]] = {
    import cats.implicits._

    api.possibleUsagePlans.find(plan => plan.id == subscription.plan) match {
      case None => FastFuture.successful(Left(PlanNotFound))
      case Some(plan) => plan.otoroshiTarget.map(_.otoroshiSettings).flatMap { id =>
        tenant.otoroshiSettings.find(_.id == id)
      } match {
        case None => Future.successful(Left(OtoroshiSettingsNotFound))
        case Some(otoSettings) =>
          implicit val otoroshiSettings: OtoroshiSettings = otoSettings
          plan.otoroshiTarget.map(_.serviceGroup) match {
            case None => Future.successful(Left(ApiNotLinked))
            case Some(groupId) =>
              otoroshiClient
                .getServiceGroup(groupId.value)
                .flatMap(group => {
                  val groupId = (group \ "id").as[String]

                  val r: EitherT[Future, AppError, JsObject] = for {
                    apiKey <- EitherT(
                      otoroshiClient.getApikey(groupId,
                        subscription.apiKey.clientId))
                      .leftMap(err => OtoroshiError(JsError.toJson(err)))
                    _ <- EitherT.liftF(
                      otoroshiClient.updateApiKey(groupId,
                        apiKey.copy(
                          authorizedGroup = groupId,
                          throttlingQuota = subscription.customMaxPerSecond.getOrElse(apiKey.throttlingQuota),
                          dailyQuota = subscription.customMaxPerDay.getOrElse(apiKey.dailyQuota),
                          monthlyQuota = subscription.customMaxPerMonth.getOrElse(apiKey.monthlyQuota),
                          metadata = apiKey.metadata ++ subscription.customMetadata
                            .flatMap(_.asOpt[Map[String, String]])
                            .getOrElse(Map.empty[String, String]),
                          readOnly = subscription.customReadOnly.getOrElse(apiKey.readOnly)
                        )))
                    _ <- EitherT.liftF(
                      env.dataStore.apiSubscriptionRepo
                        .forTenant(tenant.id)
                        .save(subscription)
                    )
                  } yield {
                    Json.obj("done" -> true,
                      "subscription" -> subscription.asSafeJson)
                  }

                  r.value
                })
          }
      }
    }
  }

  def deleteApiKey(tenant: Tenant,
                   subscription: ApiSubscription,
                   plan: UsagePlan,
                   api: Api,
                   team: Team): Future[Either[AppError, JsObject]] = {
    def deleteKey(api: Api, team: Team, group: JsObject)(
      implicit otoroshiSettings: OtoroshiSettings
    ): Future[Either[AppError, JsObject]] = {
      import cats.implicits._

      val groupId = (group \ "id").as[String]

      val r: EitherT[Future, AppError, JsObject] = for {
        _ <- EitherT.liftF(
          otoroshiClient.deleteApiKey(groupId, subscription.apiKey.clientId))
        _ <- EitherT.liftF(
          env.dataStore.apiSubscriptionRepo
            .forTenant(tenant.id)
            .deleteByIdLogically(subscription.id))
        _ <- EitherT.liftF(
          env.dataStore.teamRepo
            .forTenant(tenant.id)
            .save(team.copy(subscriptions =
              team.subscriptions.filterNot(_ == subscription.id)))
        )
      } yield {
        Json.obj("archive" -> "done",
          "subscriptionId" -> subscription.id.asJson)
      }

      r.value
    }

    plan.otoroshiTarget.map(_.otoroshiSettings).flatMap { id =>
      tenant.otoroshiSettings.find(_.id == id)
    } match {
      case None => Future.successful(Left(OtoroshiSettingsNotFound))
      case Some(otoSettings) =>
        implicit val otoroshiSettings: OtoroshiSettings = otoSettings
        plan.otoroshiTarget.map(_.serviceGroup) match {
          case None => Future.successful(Left(ApiNotLinked))
          case Some(groupId) =>
            otoroshiClient
              .getServiceGroup(groupId.value)
              .flatMap(group => deleteKey(api, team, group))
        }
    }
  }

  def archiveApiKey(tenant: Tenant,
                    subscription: ApiSubscription,
                    plan: UsagePlan,
                    enabled: Boolean): Future[Either[AppError, JsObject]] = {
    import cats.implicits._

    plan.otoroshiTarget.map(_.otoroshiSettings).flatMap { id =>
      tenant.otoroshiSettings.find(_.id == id)
    } match {
      case None => Future.successful(Left(OtoroshiSettingsNotFound))
      case Some(otoSettings) =>
        implicit val otoroshiSettings: OtoroshiSettings = otoSettings
        plan.otoroshiTarget.map(_.serviceGroup) match {
          case None => Future.successful(Left(ApiNotLinked))
          case Some(groupId) =>
            otoroshiClient
              .getServiceGroup(groupId.value)
              .flatMap(group => {
                val groupId = (group \ "id").as[String]

                val r: EitherT[Future, AppError, JsObject] = for {
                  apiKey <- EitherT(
                    otoroshiClient.getApikey(groupId,
                      subscription.apiKey.clientId))
                    .leftMap(err => OtoroshiError(JsError.toJson(err)))
                  _ <- EitherT.liftF(
                    otoroshiClient.updateApiKey(groupId,
                      apiKey.copy(enabled = enabled)))
                  _ <- EitherT.liftF(
                    env.dataStore.apiSubscriptionRepo
                      .forTenant(tenant.id)
                      .save(subscription.copy(enabled = enabled))
                  )
                } yield {
                  Json.obj("done" -> true,
                    "subscription" -> subscription.copy(enabled = enabled).asJson)
                }

                r.value
              })
        }
    }
  }

  def regenerateApiKeySecret(tenant: Tenant,
                             subscription: ApiSubscription,
                             plan: UsagePlan,
                             api: Api,
                             team: Team,
                             user: User): Future[Either[AppError, JsObject]] = {
    import cats.implicits._

    plan.otoroshiTarget.map(_.otoroshiSettings).flatMap { id =>
      tenant.otoroshiSettings.find(_.id == id)
    } match {
      case None if api.visibility == ApiVisibility.AdminOnly =>
        val newClientSecret = IdGenerator.token(64)
        val updatedSubscription = subscription.copy(apiKey = subscription.apiKey.copy(clientSecret = newClientSecret))
        env.dataStore.apiSubscriptionRepo
          .forTenant(tenant.id)
          .save(updatedSubscription)
          .map(_ => Right(Json.obj("done" -> true, "subscription" -> updatedSubscription.asJson)))
      case None => Future.successful(Left(OtoroshiSettingsNotFound))
      case Some(otoSettings) =>
        implicit val otoroshiSettings: OtoroshiSettings = otoSettings
        plan.otoroshiTarget.map(_.serviceGroup) match {
          case None => Future.successful(Left(ApiNotLinked))
          case Some(groupId) =>
            otoroshiClient
              .getServiceGroup(groupId.value)
              .flatMap(group => {
                val groupId = (group \ "id").as[String]
                val newClientSecret = IdGenerator.token(64)
                val updatedSubscription = subscription.copy(apiKey = subscription.apiKey.copy(clientSecret = newClientSecret))

                val r: EitherT[Future, AppError, JsObject] = for {
                  subscriptionTeam <- EitherT.liftF(
                    env.dataStore.teamRepo
                      .forTenant(tenant.id)
                      .findById(subscription.team))
                  admins <- EitherT.liftF(
                    env.dataStore.userRepo.find(
                      Json.obj("_id" -> Json.obj(
                        "$in" -> JsArray(subscriptionTeam
                          .map(_.users
                            .filter(_.teamPermission == Administrator)
                            .map(_.userId.asJson)
                            .toSeq
                          ).getOrElse(Seq.empty)))))
                  )
                  apiKey <- EitherT(
                    otoroshiClient.getApikey(groupId,
                      subscription.apiKey.clientId))
                    .leftMap(err => OtoroshiError(JsError.toJson(err)))
                  _ <- EitherT.liftF(
                    otoroshiClient.updateApiKey(groupId,
                      apiKey.copy(clientSecret = newClientSecret)))
                  _ <- EitherT.liftF(env.dataStore.notificationRepo
                    .forTenant(tenant.id)
                    .save(Notification(
                      id = NotificationId(BSONObjectID
                        .generate()
                        .stringify),
                      tenant = tenant.id,
                      team = Some(subscription.team),
                      sender = user,
                      action = NotificationAction
                        .ApiKeyRefresh(
                          subscription.customName.getOrElse(apiKey.clientName),
                          api.name,
                          plan.customName.getOrElse(
                            plan.typeName)),
                      notificationType =
                        NotificationType.AcceptOnly
                    )))
                  _ <- EitherT.liftF(tenant.mailer.send(
                    messagesApi("mail.apikey.refresh.title")(Lang(tenant.defaultLanguage.getOrElse("En"))),
                    admins.map(_.email),
                    messagesApi("mail.apikey.refresh.body", api.name, plan.customName.getOrElse(plan.typeName))(Lang(tenant.defaultLanguage.getOrElse("En")))
                  ))
                  _ <- EitherT.liftF(
                    env.dataStore.apiSubscriptionRepo
                      .forTenant(tenant.id)
                      .save(updatedSubscription)
                  )
                } yield {
                  Json.obj("done" -> true,
                    "subscription" -> updatedSubscription.asJson)
                }
                r.value
              })
        }
    }
  }

  def toggleApiKeyRotation(tenant: Tenant,
                           subscription: ApiSubscription,
                           plan: UsagePlan,
                           api: Api,
                           team: Team,
                           rotationEvery: Long,
                           gracePeriod: Long): Future[Either[AppError, JsObject]] = {
    import cats.implicits._

    if (api.visibility == ApiVisibility.AdminOnly) {
      Future.successful(Left(ApiKeyRotationConflict))
    } else if (plan.autoRotation.getOrElse(false)) {
      Future.successful(Left(ApiKeyRotationConflict))
    } else if (rotationEvery <= gracePeriod) {
      FastFuture.successful(Left(ApiKeyRotationError(Json.obj("error" -> "Rotation period can't ben less or equal to grace period"))))
    } else if (rotationEvery <= 0) {
      FastFuture.successful(Left(ApiKeyRotationError(Json.obj("error" -> "Rotation period can't be less or equal to zero"))))
    } else if (gracePeriod <= 0) {
      FastFuture.successful(Left(ApiKeyRotationError(Json.obj("error" -> "Grace period can't be less or equal to zero"))))
    } else {
      plan.otoroshiTarget.map(_.otoroshiSettings).flatMap { id =>
        tenant.otoroshiSettings.find(_.id == id)
      } match {
        case None => Future.successful(Left(OtoroshiSettingsNotFound))
        case Some(otoSettings) =>
          implicit val otoroshiSettings: OtoroshiSettings = otoSettings
          plan.otoroshiTarget.map(_.serviceGroup) match {
            case None => Future.successful(Left(ApiNotLinked))
            case Some(groupId) =>
              otoroshiClient
                .getServiceGroup(groupId.value)
                .flatMap(group => {
                  val groupId = (group \ "id").as[String]
                  val defaultSubscriptionRotation = ApiSubscriptionRotation()

                  val r: EitherT[Future, AppError, JsObject] = for {
                    apiKey <- EitherT(
                      otoroshiClient.getApikey(groupId,
                        subscription.apiKey.clientId))
                      .leftMap(err => OtoroshiError(JsError.toJson(err)))
                    _ <- EitherT.liftF(
                      otoroshiClient.updateApiKey(groupId,
                        apiKey.copy(rotation = apiKey.rotation.map(r => r.copy(enabled = !r.enabled)).orElse(Some(ApiKeyRotation())))))
                    _ <- EitherT.liftF(
                      env.dataStore.apiSubscriptionRepo
                        .forTenant(tenant.id)
                        .save(subscription.copy(rotation = subscription.rotation.map(r => r.copy(enabled = !r.enabled, rotationEvery = rotationEvery, gracePeriod = gracePeriod))
                          .orElse(Some(ApiSubscriptionRotation(rotationEvery = rotationEvery, gracePeriod = gracePeriod)))))
                    )
                    updatedSubscription <- EitherT.liftF(
                      env.dataStore.apiSubscriptionRepo
                        .forTenant(tenant.id)
                        .findById(subscription.id)
                    )

                  } yield {
                    Json.obj("done" -> true,
                      "subscription" -> updatedSubscription.get.asJson)
                  }
                  r.value
                })
          }
      }
    }


  }
}
