package org.mmisw.orr.ont.service

import com.mongodb.casbah.Imports._
import com.typesafe.scalalogging.{StrictLogging => Logging}
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import org.mmisw.orr.ont.db.{PwReset, User}
import org.mmisw.orr.ont._

import scala.util.{Failure, Success, Try}

class UserService(implicit setup: Setup) extends BaseService(setup) with Logging {

  createAdminIfMissing()

  private val pwrDAO = setup.db.pwrDAO

  def getUsers(query: MongoDBObject = MongoDBObject()): Iterator[User] = {
    usersDAO.find(query)
  }

  def existsUser(userName: String): Boolean = usersDAO.findOneById(userName).isDefined

  def getUser(userName: String): User = usersDAO.findOneById(userName).getOrElse(throw NoSuchUser(userName))

  def createUser(userName: String, email: String, phoneOpt: Option[String],
                 firstName: String, lastName: String, password: Either[String,String],
                 ontUri: Option[String],
                 registered: DateTime = DateTime.now(),
                 updated: Option[DateTime] = None
                ): UserResult = {

    usersDAO.findOneById(userName) match {
      case None =>
        validateUserName(userName)
        validateEmail(email)
        validatePhone(phoneOpt)

        val encPassword = password.fold(
          clearPass => userAuth.encryptPassword(clearPass),
          encPass   => encPass
        )
        val user = User(userName, firstName, lastName, encPassword, email, ontUri, phoneOpt,
                        registered = registered, updated = updated)

        Try(usersDAO.insert(user, WriteConcern.Safe)) match {
          case Success(_) =>
            sendNotificationEmail("New user registered",
              s"""
                 |The following user has been registered:
                 |
                 | Username: $userName
                 | Name: $firstName $lastName
                 | Email: $email
                 | Phone: ${phoneOpt.getOrElse("")}
                 | registered: ${user.registered}
              """.stripMargin
            )
            UserResult(userName, registered = Some(user.registered))

          case Failure(exc) => throw CannotInsertUser(userName, exc.getMessage)
              // perhaps duplicate key in concurrent registration
        }

      case Some(_) => throw UserAlreadyRegistered(userName)
    }
  }

  def updateUser(userName: String,
                 registered: Option[DateTime] = None,
                 updated: Option[DateTime] = None,
                 map: Map[String,String] = Map.empty
                ): UserResult = {

    var update = getUser(userName)

    if (map.contains("email")) {
      update = update.copy(email = map("email"))
    }
    if (map.contains("phone")) {
      update = update.copy(phone = map.get("phone"))
    }
    if (map.contains("firstName")) {
      update = update.copy(firstName = map("firstName"))
    }
    if (map.contains("lastName")) {
      update = update.copy(lastName = map("lastName"))
    }

    if (map.contains("password")) {
      val encPassword = userAuth.encryptPassword(map("password"))
      update = update.copy(password = encPassword)
    }
    else if (map.contains("encPassword")) {
      update = update.copy(password = map("encPassword"))
    }

    map.get("ontIri") orElse map.get("ontUri") foreach { x ⇒
      update = update.copy(ontUri = Some(x))
    }

    registered foreach {u => update = update.copy(registered = u)}
    updated    foreach {u => update = update.copy(updated = Some(u))}

    //logger.debug(s"updating user with: $update")

    Try(usersDAO.update(MongoDBObject("_id" -> userName), update, false, false, WriteConcern.Safe)) match {
      case Success(result) =>
        UserResult(userName, updated = update.updated)

      case Failure(exc)  => throw CannotUpdateUser(userName, exc.getMessage)
    }
  }

  /**
   * Sends email with reminder of username(s).
   */
  def sendUsername(email: String): Unit = {
    logger.debug(s"sendUsername: email=$email")

    val dtFormatter = DateTimeFormat.forPattern("YYYY-MM-dd")
    def getEmailText(users: Seq[db.User]): String = {

      val fmt = "%s - %-12s - %s"
      val header = fmt.format("Registered", "Username", "Full name")
      def user2info(u: db.User): String =
        fmt.format(dtFormatter.print(u.registered), u.userName, u.firstName+ " " + u.lastName)

      val be = if (users.size > 1) "s are" else " is"
      s"""
        |Hi $email,
        |
        |A request has been received to send a reminder of account information associated with your email address.
        |
        |The following account$be associated:
        |    $header
        |    ${users.map(user2info).mkString("\n    ")}
        |
        |The ${setup.instanceName} team
        """.stripMargin
    }

    val query = MongoDBObject("email" -> email)
    val users = usersDAO.find(query).toSeq.sortBy(_.registered.toDate)

    if (users.nonEmpty) {
      val emailText = getEmailText(users)
      logger.debug(s"sendUsername: email=$email: emailText:\n$emailText")
      try {
        setup.emailer.sendEmail(email,
          s"Your username${if (users.size > 1) "s" else ""}",
          emailText)
      }
      catch {
        case exc:Exception => exc.printStackTrace()
      }
    }
    else logger.debug(s"sendUsername: email=$email: no associated usernames")
  }

  /**
   * Generates email so the user can reset her/his password.
   */
  def requestResetPassword(user: db.User, resetRoute: String): Unit = {
    logger.debug(s"request password reset for userName=${user.userName} (resetRoute=$resetRoute)")

    def getEmailText(resetLink: String): String = {
      s"""
        | Hi ${user.firstName} ${user.lastName},
        |
        | You have requested to reset your password at the ${setup.instanceName}.
        |
        | Please visit this link to reset it:
        |   $resetLink
        |
        | Your account:
        |    username: ${user.userName}
        |    email:    ${user.email}
        |
        | If you did not make this request, please disregard this email.
        |
        | The ${setup.instanceName} team
        """.stripMargin
    }

    val token = java.util.UUID.randomUUID().toString

    val expiration = DateTime.now().plusHours(24)
    val obj = PwReset(token, user.userName, expiration)

    Try(pwrDAO.insert(obj, WriteConcern.Safe)) match {
      case Success(r) =>
        val emailText = getEmailText(s"$resetRoute$token")
        logger.debug(s"resetPassword: PwReset: $obj emailText:\n$emailText")
        try {
          setup.emailer.sendEmail(user.email,
            s"Reset your ${setup.instanceName} password",
            emailText)
        }
        catch {
          case exc:Exception => exc.printStackTrace()
        }

      case Failure(exc) => exc.printStackTrace()
    }
  }

  def notifyPasswordHasBeenReset(user: db.User): Unit = {
    val emailText =
      s"""
         |Your password has been changed.
         |
         | Your account:
         |    username: ${user.userName}
         |    email:    ${user.email}
         |
         | The ${setup.instanceName} team
       """.stripMargin
    logger.debug(s"notifyPasswordReset:\n$emailText")

    try {
      setup.emailer.sendEmail(user.email,
        s"${setup.instanceName} password change confirmation",
        emailText
      )
    }
    catch {
      case exc:Exception => exc.printStackTrace()
    }
  }

  def deleteUser(userName: String) = {
    val user = getUser(userName)

    Try(usersDAO.remove(user, WriteConcern.Safe)) match {
      case Success(result) =>
        UserResult(userName, removed = Some(DateTime.now())) //TODO

      case Failure(exc)  => throw CannotDeleteUser(userName, exc.getMessage)
    }
  }

  def deleteAll() = usersDAO.remove(MongoDBObject())

  ///////////////////////////////////////////////////////////////////////////

  /*
   * TODO validate userName
   *  For migration from previous database note that there are userNames
   *  with spaces, with periods, and even some emails.
   * Actually, email would be a desirable ID in general: review this.
   **/
  private def validateUserName(userName: String) {
  }

  // TODO validate email
  private def validateEmail(email: String) {
  }

  // TODO validate phone
  private def validatePhone(phoneOpt: Option[String]) {
  }

  /**
   * Creates the "admin" user if not already in the database.
   */
  def createAdminIfMissing() {
    val admin = "admin"
    usersDAO.findOneById(admin) match {
      case None =>
        logger.debug("creating 'admin' user")
        val password    = setup.cfg.admin.password
        val encPassword = userAuth.encryptPassword(password)
        val email       = setup.cfg.admin.email
        val obj = db.User(admin, "Adm", "In", encPassword, email)

        Try(usersDAO.insert(obj, WriteConcern.Safe)) match {
          case Success(r) =>
            logger.info(s"'admin' user created: ${obj.registered}")

          case Failure(exc)  =>
            logger.error(s"error creating 'admin' user", exc)
        }

      case Some(_) => // nothing
    }
  }

}
