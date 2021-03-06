package mono.core.image

import java.nio.file.Path

import cats.data.NonEmptyList
import cats.~>
import doobie.imports.Transactor
import doobie.util.update.Update0
import monix.eval.Task
import doobie.imports._
import cats.implicits._
import fs2.interop.cats._

object ImagesDoobieInterpreter {
  val createTable: Update0 =
    sql"CREATE TABLE  IF NOT EXISTS images(id SERIAL PRIMARY KEY, hash VARCHAR(32) NOT NULL, size BIGINT NOT NULL, person_id INTEGER REFERENCES persons NOT NULL, created_at TIMESTAMP, sub_type VARCHAR(16) NOT NULL, caption VARCHAR(512), width INTEGER, height INTEGER)".update

  val createHashIndex: Update0 =
    sql"CREATE INDEX IF NOT EXISTS images_hash_idx ON images USING HASH(hash)".update

  def init(xa: Transactor[Task]): Task[Int] =
    (createTable.run *> createHashIndex.run).transact(xa)

  def insertImageQuery(image: Image): ConnectionIO[Int] =
    {
      import image._
      sql"INSERT INTO images(hash,size,person_id,created_at,sub_type,caption,width,height) VALUES($hash,$size,$personId,$createdAt,$subType,$caption,$width,$height)"
        .update.withUniqueGeneratedKeys("id")
    }

  val select: Fragment =
    sql"SELECT id,hash,size,person_id,created_at,sub_type,caption,width,height FROM images "

  def getImageQuery(id: Long): Query0[Image] =
    (select ++ fr" WHERE id=$id").query

  def findImageByHashQuery(personId: Long, hash: String): Query0[Image] =
    (select ++ fr" WHERE person_id=$personId AND hash=$hash").query

  def getImagesQuery(ids: NonEmptyList[Int]): Query0[Image] =
    (select ++ fr" WHERE " ++ Fragments.in(fr"id", ids)).query
}

class ImagesDoobieInterpreter(xa: Transactor[Task], baseDir: Path) extends (ImageOp ~> Task) {
  import ImagesDoobieInterpreter._

  override def apply[A](fa: ImageOp[A]): Task[A] = fa match {
    case StoreImage(userId, file, caption) ⇒
      Image.build(userId, file, caption).flatMap[Either[String, Image]] {
        case Right(im) ⇒
          (for {
            maybeImage ← findImageByHashQuery(im.personId, im.hash).option
            image ← maybeImage match {
              case Some(i) ⇒
                // TODO: update caption?
                Right(i).pure[ConnectionIO]
              case None ⇒
                insertImageQuery(im)
                  .map{ id ⇒
                    val image = im.copy(id = id)
                    Image.storeFile(baseDir, image, file)
                    Right(image)
                  }
            }
          } yield image).transact(xa)

        case Left(l) ⇒
          Task.now(Left(l))
      }.asInstanceOf[Task[A]]

    case GetImageFile(image) ⇒
      // TODO: what if there's no image?
      Task.now(baseDir.resolve(image.fileDir).resolve(image.fileName).asInstanceOf[A])

    case FindImage(imageId) ⇒
      getImageQuery(imageId).option.transact(xa).asInstanceOf[Task[A]]

    case GetImageById(imageId) ⇒
      getImageQuery(imageId).unique.transact(xa).asInstanceOf[Task[A]]

    case GetImagesByIds(imageIds) ⇒
      NonEmptyList.fromList(imageIds.toList).fold(Task.now(Nil.asInstanceOf[A]))(nel ⇒
        getImagesQuery(nel).list.transact(xa).asInstanceOf[Task[A]])
  }
}
