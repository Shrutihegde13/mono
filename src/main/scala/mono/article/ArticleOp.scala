package mono.article

import java.time.Instant

sealed trait ArticleOp[T]

case class CreateArticle(
  authorId:  Int,
  title:     String,
  createdAt: Instant
) extends ArticleOp[Article]

case class FetchArticles(
  authorId: Option[Int],
  q:        Option[String],
  offset:   Int,
  limit:    Int
) extends ArticleOp[Articles]

case class FetchDrafts(
  authorId: Int,
  offset:   Int,
  limit:    Int
) extends ArticleOp[Articles]

case class PublishDraft(id: Int) extends ArticleOp[Article]

case class DraftArticle(id: Int) extends ArticleOp[Article]

case class GetArticleById(id: Int) extends ArticleOp[Article]

case class GetText(id: Int) extends ArticleOp[String]

case class SetText(id: Int, text: String) extends ArticleOp[String]

case class UpdateArticle(id: Int, title: String, headline: Option[String], publishedYear: Option[Int]) extends ArticleOp[Article]

case class SetTitle(id: Int, text: String) extends ArticleOp[Article]

case class SetHeadline(id: Int, text: Option[String]) extends ArticleOp[Article]

case class SetCover(id: Int, coverId: Option[Int]) extends ArticleOp[Article]