import it.unibo.collektive.aggregate.api.Aggregate
import it.unibo.collektive.Collektive.Companion.aggregate

fun Aggregate<Int>.entry() {
    alignedOn(0) {
        listOf(1,2,3).forEach {
            neighboring(0)
        }
    }
}