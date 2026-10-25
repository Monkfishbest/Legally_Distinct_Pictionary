import com.google.inject.AbstractModule
import persistence.{GamePersistence, ImageStorage, JdbcGamePersistence, JdbcTurnImageRepository, JdbcWordRepository, LocalImageStorage, TurnImageRepository, WordRepository}
import services.RoomService

class Module extends AbstractModule:
  override def configure(): Unit =
    bind(classOf[WordRepository]).to(classOf[JdbcWordRepository])
    bind(classOf[GamePersistence]).to(classOf[JdbcGamePersistence])
    bind(classOf[ImageStorage]).to(classOf[LocalImageStorage])
    bind(classOf[TurnImageRepository]).to(classOf[JdbcTurnImageRepository])
    bind(classOf[RoomService]).asEagerSingleton()
