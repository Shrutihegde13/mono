package mono

import mono.core.alias.Alias
import mono.core.article.ArticleOps
import monix.execution.Scheduler.Implicits.global

class CreateDraftScenarioSpec
    extends ScriptSpec {

  "bot" should {
    val A = ArticleOps.ops[Interpret.Op]

    "alias" in {
      Alias.normalize(Some("alari")).runAsync.futureValue shouldBe Some("alari")
    }

    "create article with single message" in {
      "/new test single".!!

      eventually {
        A.fetchDrafts(0, 0, 100).eval.values.head.title shouldBe "test single"
      }
    }

    "create article with additional message" ignore {
      "/new".!!

      "test title".!!

      eventually {
        A.fetchDrafts(0, 0, 100).eval.values.map(_.title).should(contain("test title"))
      }

    }
  }

}
