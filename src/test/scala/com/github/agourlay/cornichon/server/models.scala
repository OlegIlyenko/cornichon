package com.github.agourlay.cornichon.server

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import spray.json.DefaultJsonProtocol

import scala.collection.mutable
import scala.concurrent._

case class Publisher(name: String, foundationDate: String, location: String)

case class SuperHero(name: String, realName: String, city: String, publisher: String)

class TestData(implicit executionContext: ExecutionContext) {

  def addPublisher(p: Publisher) = Future {
    if (publishers.exists(_.name == p.name))
      throw new PublisherAlreadyExists(p.name)
    else {
      publishers.+=(p)
      p
    }
  }

  def addSuperhero(s: SuperHero) = {
    publisherByName(s.publisher).map { p ⇒
      if (superHeroes.exists(_.name == s.name))
        throw new SuperHeroAlreadyExists(s.name)
      else {
        superHeroes.+=(s)
        s
      }
    }
  }

  def publisherByName(name: String) = Future {
    publishers.find(_.name == name).fold(throw new PublisherNotFound(name)) { c ⇒ c }
  }

  def superheroByName(name: String) = Future {
    superHeroes.find(_.name == name).fold(throw new SuperHeroNotFound(name)) { c ⇒ c }
  }

  def allPublishers = Future { publishers.toSeq }

  def allSuperheroes = Future { superHeroes.toSeq }

  val publishers = mutable.MutableList(
    Publisher("DC", "1934", "Burbank, California"),
    Publisher("Marvel", "1939", "135 W. 50th Street, New York City")
  )

  val superHeroes = mutable.MutableList(
    SuperHero("Batman", "Bruce Wayne", "Gotham city", "DC"),
    SuperHero("Superman", "Clark Kent", "Metropolis", "DC"),
    SuperHero("Spiderman", "Peter Parker", "New York", "Marvel"),
    SuperHero("Iron Man", "Tony Stark", "New York", "Marvel")
  )

}

trait ResourceNotFound extends Exception {
  val id: String
}
case class PublisherNotFound(id: String) extends ResourceNotFound
case class SuperHeroNotFound(id: String) extends ResourceNotFound

trait ResourceAlreadyExists extends Exception {
  val id: String
}
case class PublisherAlreadyExists(id: String) extends ResourceNotFound
case class SuperHeroAlreadyExists(id: String) extends ResourceNotFound

case class HttpError(error: String)

trait JsonSupport extends DefaultJsonProtocol with SprayJsonSupport {
  implicit val formatCP = jsonFormat3(Publisher)
  implicit val formatSH = jsonFormat4(SuperHero)
  implicit val formatHE = jsonFormat1(HttpError)
}