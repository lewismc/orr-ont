package org.mmisw.orr.ont

import java.io.File

import com.mongodb.casbah.Imports._
import com.typesafe.scalalogging.slf4j.Logging
import org.mmisw.orr.ont.db.{Ontology, OntologyVersion}
import org.mmisw.orr.ont.swld.ontUtil


abstract class OntError(val message: String) extends Error

abstract class NoSuch(msg: String) extends OntError(msg)

case class NoSuchOntUri(uri: String)
  extends NoSuch(s"uri=$uri: No such ontology")

case class NoSuchOntVersion(uri: String, version: String)
  extends NoSuch(s"uri=$uri version=$version: No such ontology version")

case class NoSuchOntFormat(uri: String, version: String, format: String)
  extends NoSuch(s"uri=$uri version=$version format=$format: No such ontology format")

case class CannotCreateFormat(uri: String, version: String, format: String, msg: String)
  extends OntError(s"uri=$uri version=$version format=$format: Cannot create requested ontology format: $msg")

case class Bug(msg: String) extends OntError(msg)


/**
 * Ontology service
 */
class OntService(implicit setup: Setup) extends BaseService(setup) with Logging {

  /**
   * Returns ontology elements for a given URI and optional version.
   *
   * @param uri         ontology URI
   * @param versionOpt  optional version. If None, latest version is returned
   * @return            (ont, ontVersion, version)
   */
  def resolveOntology(uri: String, versionOpt: Option[String]): (Ontology, OntologyVersion, String) = {

    val ont = getOnt(uri)

    versionOpt match {
      case Some(version) =>
        val ontVersion = ont.versions.getOrElse(version, throw NoSuchOntVersion(uri, version))
        (ont, ontVersion, version)

      case None =>
        val (ontVersion, version) = getLatestVersion(ont).getOrElse(throw Bug(s"'$uri', no versions registered"))
          (ont, ontVersion, version)
    }
  }

  /**
   * Get the ontologies satisfying the given query.
   * @param query  Query
   * @return       iterator
   */
  def getOntologies(query: MongoDBObject): Iterator[PendOntologyResult] = {
    ontDAO.find(query) map { ont =>
      getLatestVersion(ont) match {
        case Some((ontVersion, version)) =>
          PendOntologyResult(ont.uri, ontVersion.name, ont.orgName, ont.sortedVersionKeys)

        case None =>
          // This will be case when all versions have been deleted.
          // TODO perhaps allow ontology entry with no versions?
          logger.warn(s"bug: '${ont.uri}', no versions registered")
          PendOntologyResult(ont.uri, "?", ont.orgName, List.empty)
      }
    }
  }

  private def getOnt(uri: String) = ontDAO.findOneById(uri).getOrElse(throw NoSuchOntUri(uri))

  private def getLatestVersion(ont: Ontology): Option[(OntologyVersion,String)] = {
    ont.sortedVersionKeys.headOption match {
      case Some(version) => Some((ont.versions.get(version).get, version))
      case None => None
    }
  }

  /**
   * Gets the file for a given ontolofy.
   *
   * @param uri        ontology URI
   * @param version    version
   * @param reqFormat  requested format
   * @return           (file, actualFormat)
   */
  def getOntologyFile(uri: String, version: String, reqFormat: String): (File,String) = {
    val uriEnc = uri.replace('/', '|')
    val uriDir = new File(ontsDir, uriEnc)
    val versionDir = new File(uriDir, version)
    val actualFormat = ontUtil.storedFormat(reqFormat)

    val file = new File(versionDir, s"file.$actualFormat")

    if (file.canRead) {
      // already exists, just return it
      (file, actualFormat)
    }
    else try {
      // TODO determine base format for conversions
      val fromFile = new File(versionDir, "file.rdf")
      ontUtil.convert(uri, fromFile, fromFormat = "rdf", file, toFormat = actualFormat) match {
        case Some(resFile) => (resFile, actualFormat)
        case _ => throw NoSuchOntFormat(uri, version, reqFormat) // TODO include accepted formats
      }
    }
    catch {
      case exc: Exception => // likely com.hp.hpl.jena.shared.NoWriterForLangException
        val exm = s"${exc.getClass.getName}: ${exc.getMessage}"
        throw CannotCreateFormat(uri, version, reqFormat, exm)
    }
  }

  private val baseDir = setup.filesConfig.getString("baseDirectory")
  private val ontsDir = new File(baseDir, "onts")

}
