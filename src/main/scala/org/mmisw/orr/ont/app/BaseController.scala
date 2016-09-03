package org.mmisw.orr.ont.app

import org.mmisw.orr.ont.auth.{AuthUser, AuthenticationSupport}
import org.mmisw.orr.ont.service.{OrgService, JwtUtil, NoSuchUser, UserService}
import org.mmisw.orr.ont.{Setup, db}
import org.scalatra.auth.strategy.BasicAuthStrategy

import scala.util.{Failure, Success, Try}


abstract class BaseController(implicit setup: Setup) extends OrrOntStack
  with AuthenticationSupport with SimpleMongoDbJsonConversion {

//  val secretKey = setup.config.getString("api.secret")
//  var signedRequest = false

  protected val extra: List[String] = setup.cfg.admin.extra match {
    case Some(extraString) => extraString.split("\\s*,\\s*").toList
    case None              => List.empty
  }

  // assigned in the before filter
  protected var authenticatedUser: Option[AuthUser] = None

  protected val orgsDAO     = setup.db.orgsDAO
  protected val usersDAO    = setup.db.usersDAO
  protected val ontDAO      = setup.db.ontDAO

  protected val userService = new UserService
  protected val orgService = new OrgService

  protected val userAuth    = setup.db.authenticator

  protected val jwtUtil = new JwtUtil(setup.cfg.auth.secret)

  ///////////////////////////////////////////////////////////////////////////

  before() {
    // println("---- Authorization = " + request.getHeader("Authorization"))
    authenticatedUser = {
      // try basic auth, then JWT, to see if we have an authenticated user
      val baReq = new BasicAuthStrategy.BasicAuthRequest(request)
      if (baReq.providesAuth && baReq.isBasicAuth) {
        scentry.authenticate("Basic")
      }
      else for {
        jwt <- getFromParamsOrBody("jwt")
        authUser <- jwtUtil.verifyToken(jwt)
      } yield authUser
    }
  }

  protected def requireAuthenticatedUser = authenticatedUser.getOrElse(bug("authenticatedUser should be defined"))

  protected def getFromParamsOrBody(name: String): Option[String] = {
    if (params.contains(name)) params.get(name)
    else for (body <- bodyOpt(); value <- getString(body, name)) yield value
  }

  protected def checkIsExtra: Boolean = authenticatedUser match {
    case Some(u) => extra.contains(u.userName)
    case None    => false
  }

  protected def checkIsAdminOrExtra: Boolean = authenticatedUser match {
    case Some(u) => "admin" == u.userName || extra.contains(u.userName)
    case None    => false
  }

  /**
   * True only if the authenticated user (if any) is one of the given user names,
   * or is "admin", or is one of the extras.
   */
  protected def checkIsUserOrAdminOrExtra(userNames: String*): Boolean = authenticatedUser match {
    case Some(u) => userNames.contains(u.userName) || "admin" == u.userName || extra.contains(u.userName)
    case None    => false
  }

  protected def checkIsUserOrAdminOrExtra(userNames: Set[String]): Boolean = authenticatedUser match {
    case Some(u) => userNames.contains(u.userName) || "admin" == u.userName || extra.contains(u.userName)
    case None    => false
  }

  protected def verifyIsAdminOrExtra(): AuthUser = {
    val u = authenticatedUser.getOrElse(halt(401, s"unauthorized"))
    val ok = "admin" == u.userName || extra.contains(u.userName)
    if (ok) u else halt(403, s"unauthorized")
  }

  protected def verifyIsUserOrAdminOrExtra(userNames: Set[String]): AuthUser = {
    val u = authenticatedUser.getOrElse(halt(401, s"unauthorized"))
    val ok = userNames.contains(u.userName) || "admin" == u.userName || extra.contains(u.userName)
    if (ok) u else halt(403, s"unauthorized")
  }

//  ///////////////////////////////////////////////////////////////////////////
//  /**
//   * authenticates a user
//   */
//  protected def createSession(userNameOpt: Option[String], passwordOpt: Option[String]): String = {
//    val userName = List(userNameOpt, passwordOpt) match {
//      case List(Some(un), Some(pw)) =>
//        userAuth.authenticateUser(un, pw).getOrElse(halt(401, "Unauthenticated"))
//        un
//      case _ =>
//        basicAuth
//        user.userName
//    }
//    session.setAttribute("userName", userName)
////    val oneYear = 365 * 24 * 3600
////    val value = UUID.randomUUID().toString.replaceAllLiterally("-", "")
////    cookies.set("orront", value)(CookieOptions(maxAge = oneYear, httpOnly = true, path = "/"))
//    userName
//  }
//
//  /**
//   * verifies the given user is logged in.
//   */
//  protected def verifySession(userName: String): Unit = {
//    sessionOption match {
//      case Some(s) =>
//        val sUserName = s.getAttribute("userName").asInstanceOf[String]
//        if (sUserName != userName) halt(403, "unauthorized")
//      case None =>
//        halt(401, "Unauthenticated")
//    }
//  }
//
//  /**
//   * checks if the the current session (if any) corresponds to a user
//   * in the given list.
//   */
//  protected def checkSession(userNames: String*): Boolean = {
//    sessionOption match {
//      case Some(s) => userNames.contains(s.getAttribute("userName").asInstanceOf[String])
//      case None    => false
//    }
//  }
//  /**
//   * checks if the the current session (if any) corresponds to a user
//   * in the given list.
//   */
//  protected def checkSession(userNames: Set[String]): Boolean = {
//    sessionOption match {
//      case Some(s) => userNames.contains(s.getAttribute("userName").asInstanceOf[String])
//      case None    => false
//    }
//  }

  protected def getUserOpt(userName: String): Option[db.User] = {
    Try(userService.getUser(userName)) match {
      case Success(res)            => Some(res)
      case Failure(exc: NoSuchUser) => None
      case Failure(exc)             => error500(exc)
    }
  }

  protected def getUser(userName: String): db.User = {
    Try(userService.getUser(userName)) match {
      case Success(res)            => res
      case Failure(exc: NoSuchUser) => error(404, s"'$userName' user is not registered")
      case Failure(exc)             => error500(exc)
    }
  }

  protected def verifyUser(userName: String): db.User = {
    getUser(userName)
  }

  protected def verifyUser(userNameOpt: Option[String]): db.User = userNameOpt match {
    case None => missing("userName")
    case Some(userName) => verifyUser(userName)
  }

  protected def isAdminOrExtra(u: db.User): Boolean = "admin" == u.userName || extra.contains(u.userName)
}
