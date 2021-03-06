package mono

import java.nio.file.Files

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ HttpRequest, Uri }
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ FileIO, Source }
import cats.free.Free
import cats.~>
import info.mukel.telegrambot4s.api.TelegramBot
import info.mukel.telegrambot4s.methods._
import info.mukel.telegrambot4s.models._
import monix.eval.Task
import mono.bot._

import scala.concurrent.Future

class MonoBot(
  override val token: String,
  script:             (BotState, Incoming) ⇒ Free[BotScript.Op, BotState],
  interpreter:        (BotOp ~> Task) ⇒ (BotScript.Op ~> Task)
)(implicit override val system: ActorSystem, override val materializer: ActorMaterializer)
    extends TelegramBot {

  val pollingInterval: Int = 20

  private val updatesSrc: Source[Update, NotUsed] = {
    type Offset = Long
    type Updates = Seq[Update]
    type OffsetUpdates = Future[(Offset, Updates)]

    val seed: OffsetUpdates = Future.successful((0L, Seq.empty[Update]))

    val iterator = Iterator.iterate(seed) {
      _ flatMap {
        case (offset, updates) ⇒
          val maxOffset = updates.map(_.updateId).fold(offset)(_ max _)
          request(GetUpdates(Some(maxOffset + 1), timeout = Some(pollingInterval)))
            .recover {
              case e: Exception ⇒
                println(Console.RED + e + Console.RESET)
                logger.error("GetUpdates failed", e)
                Seq.empty[Update]
            }
            .map {
              (maxOffset, _)
            }
      }
    }

    val parallelism = Runtime.getRuntime.availableProcessors()

    val updateGroups =
      Source.fromIterator(() ⇒ iterator)
        .mapAsync(parallelism)(
          _ map {
            case (_, updates) ⇒ updates
          }
        )

    updateGroups.mapConcat(_.to) // unravel groups
  }

  val botOpInt: BotOp ~> Task = new (BotOp ~> Task) {
    override def apply[A](fa: BotOp[A]): Task[A] = {
      logger.info(s"Going to apply: " + fa)
      fa match {
        case Say(text, chatId) ⇒
          Task.fromFuture(request(
            SendMessage(
              Left(chatId),
              text,
              Some(ParseMode.Markdown),
              None,
              None,
              None,
              None
            )
          )).map(m ⇒ m.messageId).asInstanceOf[Task[A]]

        case Reply(text, meta, forceReply) ⇒
          Task.fromFuture(request(
            SendMessage(
              Left(meta.chat.id),
              text,
              Some(ParseMode.Markdown),
              None,
              None,
              Some(meta.messageId),
              if (forceReply) Some(ForceReply(forceReply = true, selective = Some(true)))
              else None
            )
          )).map(m ⇒ m.messageId).asInstanceOf[Task[A]]

        case Choose(text, variants, chatId) ⇒
          Task.fromFuture(request(
            SendMessage(
              Left(chatId),
              text,
              Some(ParseMode.Markdown),
              None,
              None,
              None,
              Some(ReplyKeyboardMarkup(
                variants.map(_.map(KeyboardButton(_))),
                oneTimeKeyboard = Some(true)
              ))
            )
          )).map(m ⇒ m.messageId).asInstanceOf[Task[A]]

        case Inline(text, buttons, chatId, None) ⇒
          Task.fromFuture(request(
            SendMessage(
              Left(chatId),
              text,
              Some(ParseMode.Markdown),
              None,
              None,
              None,
              Some(InlineKeyboardMarkup(
                buttons.map(_.map{
                  case Inline.UrlButton(t, url) ⇒
                    InlineKeyboardButton(t, url = Some(url))
                  case Inline.CallbackButton(t, callback) ⇒
                    InlineKeyboardButton(t, callbackData = Some(callback))
                })
              ))
            )
          )).map(m ⇒ m.messageId).asInstanceOf[Task[A]]

        case Inline(text, buttons, chatId, Some(Left(msgId))) ⇒
          Task.fromFuture(request(
            SendMessage(
              Left(chatId),
              text,
              Some(ParseMode.Markdown),
              None,
              None,
              Some(msgId),
              Some(InlineKeyboardMarkup(
                buttons.map(_.map{
                  case Inline.UrlButton(t, url) ⇒
                    InlineKeyboardButton(t, url = Some(url))
                  case Inline.CallbackButton(t, callback) ⇒
                    InlineKeyboardButton(t, callbackData = Some(callback))
                })
              ))
            )
          )).map(m ⇒ m.messageId).asInstanceOf[Task[A]]

        case Inline(_, buttons, chatId, Some(Right((callbackId, msgId)))) ⇒
          Task.fromFuture(request(
            EditMessageReplyMarkup(
              Some(Left(chatId)),
              Some(msgId),
              Some(callbackId),
              Some(InlineKeyboardMarkup(
                buttons.map(_.map{
                  case Inline.UrlButton(t, url) ⇒
                    InlineKeyboardButton(t, url = Some(url))
                  case Inline.CallbackButton(t, callback) ⇒
                    InlineKeyboardButton(t, callbackData = Some(callback))
                })
              ))
            )
          )).map(m ⇒ m.left.map(_.messageId).getOrElse(0l)).asInstanceOf[Task[A]]

        case LoadFile(fileId) ⇒
          for {
            f ← Task.fromFuture(
              request(GetFile(fileId))
            )
            uri = Uri(s"https://api.telegram.org/file/bot$token/${f.filePath.get}")
            path = Files.createTempFile(fileId, "telegram")
            fi ← Task.fromFuture(
              Source
                .single(HttpRequest(uri = uri))
                .via(Http().outgoingConnectionHttps("api.telegram.org"))
                .flatMapConcat(_.entity.dataBytes)
                .runWith(FileIO.toPath(path))
            )
          } yield path.asInstanceOf[A]

        case InlineAnswer(text, callbackId, _) ⇒
          Task.fromFuture(
            request(AnswerCallbackQuery(callbackId, text, None, None, None))
          ).map(_ ⇒ ().asInstanceOf[A])
      }
    }
  }

  override def run(): Unit =
    updatesSrc.collect {
      case u if u.message.isDefined       ⇒ Incoming.telegram(u.message.get)
      case u if u.editedMessage.isDefined ⇒ Incoming.telegram(u.editedMessage.get, isUpdate = true)
      case u if u.callbackQuery.isDefined ⇒ Incoming.inline(u.callbackQuery.get)
    }.to(BotProcessor(script, interpreter(botOpInt))).run()

  override def shutdown(): Future[Unit] = Future.successful(())
}