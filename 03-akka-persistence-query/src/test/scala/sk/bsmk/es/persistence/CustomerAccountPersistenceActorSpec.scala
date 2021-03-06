package sk.bsmk.es.persistence

import akka.actor.ActorSystem
import akka.pattern.ask
import akka.stream.{ActorMaterializer, Materializer}
import akka.util.Timeout
import org.jooq.DSLContext
import org.scalatest.{DoNotDiscover, Matchers, WordSpec}
import sk.bsmk.customer.commands.{AddPoints, BuyVoucher, CreateAccount}
import sk.bsmk.customer.vouchers.{Voucher, VoucherRegistry}
import sk.bsmk.es.persistence.CustomerAccountPersistenceActor.GetState
import sk.bsmk.es.persistence.model.Tables.{CUSTOMER_ACCOUNTS, VOUCHERS}

import scala.concurrent.Await
import scala.concurrent.duration._

@DoNotDiscover
class CustomerAccountPersistenceActorSpec extends WordSpec with Matchers {

  val voucher = Voucher("voucher-a", 10, 123.12)
  VoucherRegistry.add(voucher)

  val dsl: DSLContext                         = JooqCustomerRepository.dsl
  val repository: JooqCustomerRepository.type = JooqCustomerRepository

  dsl.execute("DELETE FROM PUBLIC.\"snapshot\"")
  dsl.execute("DELETE FROM PUBLIC.\"journal\"")
  dsl.deleteFrom(VOUCHERS).execute()
  dsl.deleteFrom(CUSTOMER_ACCOUNTS).execute()

  "Customer account persistent actor" when {
    "commands are consumed" should {
      "change state and projections" in {

        implicit val actorSystem: ActorSystem   = ActorSystem("es-system")
        implicit val materializer: Materializer = ActorMaterializer()
        val account                             = actorSystem.actorOf(CustomerAccountPersistenceActor.props("customer-1"), "customer-1")

        implicit val timeout: Timeout = Timeout(60.seconds)
        def printState(): Unit = {
          val state = Await.result(account ? GetState, timeout.duration)
          pprint.pprintln(state)
        }

        account ! CreateAccount
        printState()
        account ! AddPoints(100)
        printState()
        account ! BuyVoucher(voucher.code)
        printState()

        val result = dsl.fetch("SELECT * FROM PUBLIC.\"journal\"")
        result should have size 3

        val consumer = ReadJournalConsumer(actorSystem)

        Thread.sleep(11000)

        val customers = repository.listCustomerAccounts()
        customers should have size 1
        val customer = customers.head

        customer.username shouldBe "customer-1"
        customer.nrOfVouchers shouldBe 1
        customer.pointBalance shouldBe 90

      }
    }
  }
}
