package org.mmisw.orr.ont.service

import org.mmisw.orr.ont.Cfg
import com.typesafe.scalalogging.{StrictLogging ⇒ Logging}
import java.util.concurrent.ConcurrentLinkedQueue

import scala.util.control.NonFatal
import java.util.{Timer, TimerTask}

import org.mmisw.orr.ont.util.IEmailer

trait INotifier {
  def sendNotificationEmail(subject: String, msg: String): Unit
  def destroy(): Unit
}

private case class Item(subject: String, msg: String)

class Notifier(cfg: Cfg, emailer: IEmailer) extends INotifier with Logging {

  def sendNotificationEmail(subject: String, msg: String): Unit = {
    queue.add(Item(subject, msg))
  }

  def destroy(): Unit = {
    dispatcher.cancel()
  }

  private val SendPeriod = 5*60*1000 // 5 minutes
  private val CheckPeriod = 30*1000  // 30 secs
  private val queue = new ConcurrentLinkedQueue[Item]
  private var latestSendTime: Long = 0

  private val timer = new Timer()
  private val dispatcher = new TimerTask {
    def run(): Unit = {
      import scala.collection.JavaConverters._
      Option(queue.peek()) foreach { _ ⇒
        if (System.currentTimeMillis - latestSendTime >= SendPeriod) {
          latestSendTime = System.currentTimeMillis
          dispatchItems(queue.synchronized {
            val items = queue.asScala.toSeq
            queue.clear()
            items
          })
        }
      }
    }
  }

  timer.schedule(dispatcher, CheckPeriod, CheckPeriod)

  private def dispatchItems(items: Seq[Item]): Unit = {
    logger.debug(s"dispatchItems: ${items.size}")
    for {
      filename ← cfg.notifications.recipientsFilename
      emails ← getEmails(filename)
      if emails.nonEmpty
    } {
      val (subject, msg) = if (items.size == 1) {
        val item = items.head
        (item.subject, item.msg)
      }
      else {
        ("Notifications", items.map(_.msg).mkString("\n\n"))
      }
      emailer.sendEmail(emails.mkString(","), subject,
        msg + "\n\n" +
        s"(You have received this email because your address is included in $filename)"
      )
    }
  }

  private def getEmails(filename: String): Option[Seq[String]] = {
    try {
      val source = io.Source.fromFile(filename)
      val emails = source.getLines.map(_.trim).filterNot { line ⇒
        line.isEmpty || line.startsWith("#")
      }
      Some(emails.toSeq)
    }
    catch {
      case _:java.io.FileNotFoundException ⇒
        logger.warn(s"sendNotificationEmail: file not found: $filename")
        None
      case NonFatal(e) ⇒
        logger.error("error sending email", e)
        None
    }
  }
}
