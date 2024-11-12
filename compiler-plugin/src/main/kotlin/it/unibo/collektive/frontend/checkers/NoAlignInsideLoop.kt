package it.unibo.collektive.frontend.checkers

import it.unibo.collektive.frontend.checkers.CheckersUtility.ALIGNED_ON_FQ_NAME
import it.unibo.collektive.frontend.checkers.CheckersUtility.discardIfFunctionDeclaration
import it.unibo.collektive.frontend.checkers.CheckersUtility.discardIfOutsideAggregateEntryPoint
import it.unibo.collektive.frontend.checkers.CheckersUtility.functionName
import it.unibo.collektive.frontend.checkers.CheckersUtility.fqName
import it.unibo.collektive.frontend.checkers.CheckersUtility.hasAggregateArgument
import it.unibo.collektive.frontend.checkers.CheckersUtility.isAggregate
import it.unibo.collektive.frontend.checkers.CheckersUtility.isFunctionCallsWithName
import it.unibo.collektive.frontend.checkers.CheckersUtility.wrappingElementsUntil
import it.unibo.collektive.frontend.visitors.FunctionCallWithAggregateParVisitor
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirWhileLoop
import org.jetbrains.kotlin.fir.references.toResolvedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.jvm.kotlinFunction

/**
 * Checker that looks for aggregate functions called inside a loop without an explicit align operation.
 */
object NoAlignInsideLoop : FirFunctionCallChecker(MppCheckerKind.Common) {

    private val safeOperators = listOf(
        ALIGNED_ON_FQ_NAME,
        "it.unibo.collektive.aggregate.api.Aggregate.align",
        "it.unibo.collektive.aggregate.api.Aggregate.dealign",
    )

    /**
     * Creates a warning for this checker, formatted with the [calleeName] that originated it.
     */
    fun createWarning(calleeName: String): String =
        """
        Warning: aggregate function '$calleeName' has been called inside a loop construct without explicit alignment.
        The same path may generate interactions more than once, leading to ambiguous alignment.
        for (element in collection) {
            $calleeName(...) // Broken
        }
        for (element in collection) {
            alignedOn(element) { // Manual alignment on element, assuming it is unique 
                $calleeName(...)
            }
        }
        """.trimIndent()


    /**
     * Getter for all Collection members using Kotlin reflection, obtaining their names as a set.
     */
    @Deprecated("This method currently raises an exception. " +
            "See https://youtrack.jetbrains.com/issue/KT-16479 for more details.")
    private fun getCollectionMembersKotlin(): Set<String> = sequenceOf(
        Class.forName("kotlin.collections.CollectionsKt").kotlin,
        Collection::class,
        Iterable::class,
        List::class,
        Map::class,
        Sequence::class,
        Set::class,
    ).flatMap { it.java.methods.mapNotNull { it.kotlinFunction } + it.members }
        .filter {
            fun KParameter.isFunctionType(): Boolean = (type.classifier as? KClass<*>)?.qualifiedName
                ?.startsWith("kotlin.Function")
                ?: false
            it.parameters.any { parameter ->
                parameter.isFunctionType()
            }
        }
        .map { it.name }
        .toSet()

    /**
     * Getter for all Collection members using Java reflection, obtaining their names as a set.
     */
    private fun getCollectionMembersJava(): Set<String> = sequenceOf(
        Class.forName("kotlin.collections.CollectionsKt"),
        Collection::class.java,
        Iterable::class.java,
        List::class.java,
        Map::class.java,
        Sequence::class.java,
        Set::class.java
    ).flatMap { it.methods.asSequence() }
        .filter { method ->
            method.parameters.any { parameter ->
                parameter.parameterizedType.typeName.startsWith("kotlin.jvm.functions.Function") ||
                        parameter.parameterizedType is Function<*>
            }
        }
        .map { it.name }
        .toSet()

    /**
     * Methods used inside collections to iterate their elements.
     */
    private val collectionMembers = getCollectionMembersJava()

    private fun CheckerContext.isInsideALoopWithoutAlignedOn(): Boolean =
        wrappingElementsUntil { it is FirWhileLoop }
            ?.discardIfFunctionDeclaration()
            ?.discardIfOutsideAggregateEntryPoint()
            ?.none(isFunctionCallsWithName("alignedOn")) ?: false

    private fun CheckerContext.isInsideIteratedFunctionWithoutAlignedOn(): Boolean =
        wrappingElementsUntil { it is FirFunctionCall && it.functionName() in collectionMembers }
            ?.discardIfFunctionDeclaration()
            ?.discardIfOutsideAggregateEntryPoint()
            ?.none(isFunctionCallsWithName("alignedOn")) ?: false

    private fun CheckerContext.isIteratedWithoutAlignedOn(): Boolean =
        isInsideALoopWithoutAlignedOn() || isInsideIteratedFunctionWithoutAlignedOn()

    @OptIn(SymbolInternals::class)
    private fun isInvalidFunWithAggregateParameter(expression: FirFunctionCall, context: CheckerContext): Boolean {
        val visitor = FunctionCallWithAggregateParVisitor(context)
        (expression.calleeReference.toResolvedFunctionSymbol()?.fir as? FirSimpleFunction)?.accept(visitor) ?: false
        return visitor.found
    }

    override fun check(
        expression: FirFunctionCall,
        context: CheckerContext,
        reporter: DiagnosticReporter,
    ) {
        val calleeName = expression.functionName()
        if (expression.fqName() in safeOperators) return
        if (expression.isAggregate(context.session) && context.isIteratedWithoutAlignedOn()) {
            reporter.reportOn(
                expression.calleeReference.source,
                CheckersUtility.PluginErrors.DOT_CALL_WARNING,
                createWarning(calleeName),
                context,
            )
        } else if (expression.hasAggregateArgument()) {
            if (context.isIteratedWithoutAlignedOn() && isInvalidFunWithAggregateParameter(expression, context)) {
                reporter.reportOn(
                    expression.calleeReference.source,
                    CheckersUtility.PluginErrors.DOT_CALL_WARNING,
                    "Warning: suspicious call of function '$calleeName' with aggregate argument inside a loop with no " +
                            "manual alignment operation",
                    context,
                )
            }
        }
    }
}
