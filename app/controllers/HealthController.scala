package controllers

import javax.inject.*
import play.api.libs.json.Json
import play.api.mvc.*

@Singleton
final class HealthController @Inject() (controllerComponents: ControllerComponents)
    extends AbstractController(controllerComponents):

  def health: Action[AnyContent] = Action:
    Ok(
      Json.obj(
        "status" -> "ok",
        "service" -> "pictionary-backend"
      )
    )
