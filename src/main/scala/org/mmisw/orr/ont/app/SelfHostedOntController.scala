package org.mmisw.orr.ont.app

import java.io.File
import javax.servlet.http.{HttpServletRequest, HttpServletRequestWrapper}

import com.typesafe.scalalogging.{StrictLogging => Logging}
import org.mmisw.orr.ont.service.{OntService, TripleStoreService}
import org.mmisw.orr.ont.Setup

/**
  * Controller to dispatch "self-resolvable" ontology/terms requests.
  *
  * Includes mechanisms for:
  * - dispatch of organization or user request (with redirection to ORR Portal)
  * - dispatch of the UI (ORR Portal) such that the
  *   application context is preserved in the browser's location address.
  * - actual self-resolution of ontology or term.
  */
class SelfHostedOntController(implicit setup: Setup,
                              ontService: OntService,
                              tsService: TripleStoreService
                             ) extends BaseOntController
    with Logging {

  get("/(.*)".r) {
    val pathInfo = request.pathInfo
    if (pathInfo.startsWith("/api")) {
      pass()
    }

    val reqFormat = getRequestedFormat

    if (logger.underlying.isDebugEnabled &&
      !looksLikeWebAppResource(pathInfo)) {
      logger.debug(s"""SelfHostedOntController:
           |reqFormat              : $reqFormat
           |request.pathInfo       : $pathInfo
           |request.getContextPath : ${request.getContextPath}
           |request.getRequestURI  : ${request.getRequestURI}
           |request.getRequestURL  : ${request.getRequestURL}
         """.stripMargin)
    }

    if (!portalDispatch(pathInfo, reqFormat)) {
      // skip leading slash if any
      val noSlash = if (pathInfo.length() > 1) pathInfo.substring(1) else pathInfo
      resolve(noSlash, reqFormat)
    }
  }

  ///////////////////////////////////////////////////////////////////////////

  /** returns true only if the dispatch is completed here */
  private def portalDispatch(pathInfo: String, reqFormat: String): Boolean = {
    // do the special HTML dispatch below only if the "/index.html" exists under my context:
    val hasIndexHtml = Option(request.getServletContext.getRealPath("/index.html")) match {
      case Some(realPath) => new File(realPath).isFile
      case _ => false
    }

    if (logger.underlying.isDebugEnabled &&
      !looksLikeWebAppResource(pathInfo)) {
      logger.debug(s"portalDispatch: hasIndexHtml=$hasIndexHtml pathInfo=$pathInfo")
    }

    def adjustedRequest(request: HttpServletRequest): HttpServletRequest = {
      if (pathInfo == "/" || pathInfo == "/sparql" || pathInfo == "/sparql/") {
        val adjustedPath = s"${pathInfo.replaceAll("/+$", "")}/index.html"
        logger.debug(s"adjustedRequest: for request=$pathInfo adjustedPath=$adjustedPath")
        contentType = formats("html") // make sure html is responded
        new HttpServletRequestWrapper(request) {
          override def getPathInfo: String = adjustedPath
        }
      }
      else {
        contentType = null  // so, "default" servlet sets the content type according to the resource
        request
      }
    }

    if (hasIndexHtml) {
      val isUiResource = List("/vendor", "/js", "/img", "/css") exists pathInfo.startsWith

      if (isUiResource || pathInfo == "/sparql" || pathInfo == "/sparql/") {
        servletContext.getNamedDispatcher("default").forward(adjustedRequest(request), response)
        true
      }
      else if (reqFormat == "html") {
        // first, see if it is a request for organization or user:
        if (pathInfo.matches(orgOrUserPattern.regex)) {
          false  // will be dispatched as such next
        }
        else {
          // dispatch /index.html
          logger.debug(s"serving '/index.html' for request: $pathInfo")
          val indexRequest = new HttpServletRequestWrapper(request) {
            override def getPathInfo: String = "/index.html"
          }
          contentType = formats("html") // make sure html is responded
          servletContext.getNamedDispatcher("default").forward(indexRequest, response)
          true
        }
      }
      else false
    }
    else false
  }

  private val orgOrUserPattern = "^/?(~?)([^/]+)$".r

  private def resolve(pathInfo: String, reqFormat: String) = {
    logger.debug(s"resolve: pathInfo='$pathInfo'")
    pathInfo match {
      case orgOrUserPattern("", xyz)  ⇒ resolveOrgOrUser(xyz, reqFormat)
      case orgOrUserPattern("~", xyz) ⇒ resolveUser(xyz, reqFormat)
      case _ ⇒
        val uri = request.getRequestURL.toString
        logger.debug(s"self-resolving '$uri' ...")
        resolveOntOrTermUri(uri, Some(reqFormat))
    }
  }

  /**
    * Dispatches organization OR user request (/&lt;context>/xyz).
    * This supports emulating the behavior in previous Ont v2 service where
    * xyx could correspond to an existing authority abbreviation, in such
    * case with dispatch of the list of associated ontologies. In this new
    * implementation we first try xyz as an organization, then as a user,
    * and then just self-resolution of possible ontology or term.
    * In the case of organization or user, a redirect is done so the actual
    * html dispatch relies on the orr-portal.
    */
  private def resolveOrgOrUser(xyz: String, reqFormat: String) = {
    logger.debug(s"resolve: xyz='$xyz'")
    orgsDAO.findOneById(xyz) match {
      case Some(org) =>
        org.ontUri match {
          case Some(ontUri) => redirect(ontUri)
          case None =>
            redirect(setup.cfg.deployment.url + "#org/" +org.orgName)
        }
      case None =>
        if (!resolveUser(xyz, reqFormat)) {
          logger.debug(s"resolveOrgOrUser: no org not user by xyz=$xyz; delegating to selfResolve")
          selfResolve(reqFormat)
        }
    }
  }

  /**
    * Tries xyz as a userName
    */
  private def resolveUser(xyz: String, reqFormat: String): Boolean = {
    logger.debug(s"resolveUser: xyz='$xyz'")
    usersDAO.findOneById(xyz) match {
      case Some(user) =>
        user.ontUri match {
          case Some(ontUri) ⇒ redirect(ontUri)
          case None         ⇒ redirect(setup.cfg.deployment.url + "#user/" +user.userName)
        }
        true
      case None ⇒ false
    }
  }

  private def selfResolve(reqFormat: String) = {
    val uri = request.getRequestURL.toString
    logger.debug(s"self-resolving '$uri' ...")
    resolveOntOrTermUri(uri, Some(reqFormat))
  }

  private def looksLikeWebAppResource(pathInfo: String): Boolean = {
    List(".html", ".js", ".css", ".map", ".woff2", ".woff").exists(pathInfo.endsWith)
  }
}
