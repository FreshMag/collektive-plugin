import it.unibo.collektive.aggregate.api.Aggregate

fun Aggregate<Int>.entry() {
    for(i in 1..3) {
        alignedOn(0) {
            neighboring(0)
        }
    }
}