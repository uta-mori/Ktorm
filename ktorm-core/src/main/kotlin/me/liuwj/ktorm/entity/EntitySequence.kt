package me.liuwj.ktorm.entity

import me.liuwj.ktorm.database.Database
import me.liuwj.ktorm.database.prepareStatement
import me.liuwj.ktorm.dsl.Query
import me.liuwj.ktorm.dsl.and
import me.liuwj.ktorm.dsl.not
import me.liuwj.ktorm.dsl.toCountExpression
import me.liuwj.ktorm.expression.ScalarExpression
import me.liuwj.ktorm.expression.SelectExpression
import me.liuwj.ktorm.schema.Table

data class EntitySequence<E : Entity<E>, T : Table<E>>(val sourceTable: T, val expression: SelectExpression) {

    val query = Query(expression)

    val sql get() = query.sql

    val rowSet get() = query.rowSet

    val totalRecords get() = query.totalRecords

    operator fun iterator() = object : Iterator<E> {
        private val queryIterator = query.iterator()

        override fun hasNext(): Boolean {
            return queryIterator.hasNext()
        }

        override fun next(): E {
            return sourceTable.createEntity(queryIterator.next())
        }
    }
}

fun <E : Entity<E>, T : Table<E>> T.asSequence(): EntitySequence<E, T> {
    val query = this.joinReferencesAndSelect()
    return EntitySequence(this, query.expression as SelectExpression)
}

fun <E : Entity<E>> EntitySequence<E, *>.toList(): List<E> {
    val list = ArrayList<E>()
    for (item in this) {
        list += item
    }
    return list
}



fun <E : Entity<E>, T : Table<E>> EntitySequence<E, T>.filter(predicate: (T) -> ScalarExpression<Boolean>): EntitySequence<E, T> {
    if (expression.where == null) {
        return this.copy(expression = expression.copy(where = predicate(sourceTable)))
    } else {
        return this.copy(expression = expression.copy(where = expression.where and predicate(sourceTable)))
    }
}

fun <E : Entity<E>, T : Table<E>> EntitySequence<E, T>.filterNot(predicate: (T) -> ScalarExpression<Boolean>): EntitySequence<E, T> {
    return this.filter { !predicate(it) }
}

fun <E : Entity<E>, T : Table<E>, C : MutableCollection<in E>> EntitySequence<E, T>.filterTo(destination: C, predicate: (T) -> ScalarExpression<Boolean>): C {
    val sequence = this.filter(predicate)
    for (item in sequence) {
        destination += item
    }
    return destination
}

fun <E : Entity<E>, T : Table<E>, C : MutableCollection<in E>> EntitySequence<E, T>.filterNotTo(destination: C, predicate: (T) -> ScalarExpression<Boolean>): C {
    val sequence = this.filterNot(predicate)
    for (item in sequence) {
        destination += item
    }
    return destination
}

fun EntitySequence<*, *>.count(): Int {
    val countExpr = expression.toCountExpression(keepPaging = true)

    countExpr.prepareStatement { statement, logger ->
        statement.executeQuery().use { rs ->
            if (rs.next()) {
                return rs.getInt(1).also { logger.debug("Count: {}", it) }
            } else {
                val (sql, _) = Database.global.formatExpression(countExpr, beautifySql = true)
                throw IllegalStateException("No result return for sql: $sql")
            }
        }
    }
}

fun <E : Entity<E>, T : Table<E>> EntitySequence<E, T>.count(predicate: (T) -> ScalarExpression<Boolean>): Int {
    return this.filter(predicate).count()
}

fun EntitySequence<*, *>.none(): Boolean {
    return this.count() == 0
}

fun <E : Entity<E>, T : Table<E>> EntitySequence<E, T>.none(predicate: (T) -> ScalarExpression<Boolean>): Boolean {
    return this.count(predicate) == 0
}

fun EntitySequence<*, *>.any(): Boolean {
    return this.count() > 0
}

fun <E : Entity<E>, T : Table<E>> EntitySequence<E, T>.any(predicate: (T) -> ScalarExpression<Boolean>): Boolean {
    return this.count(predicate) > 0
}

fun <E : Entity<E>, T : Table<E>> EntitySequence<E, T>.all(predicate: (T) -> ScalarExpression<Boolean>): Boolean {
    return this.none { !predicate(it) }
}

fun <E : Entity<E>, K, V> EntitySequence<E, *>.associate(transform: (E) -> Pair<K, V>): Map<K, V> {
    return this.associateTo(LinkedHashMap(), transform)
}

fun <E : Entity<E>, K> EntitySequence<E, *>.associateBy(keySelector: (E) -> K): Map<K, E> {
    return this.associateByTo(LinkedHashMap(), keySelector)
}

fun <E : Entity<E>, K, V> EntitySequence<E, *>.associateBy(keySelector: (E) -> K, valueTransform: (E) -> V): Map<K, V> {
    return this.associateByTo(LinkedHashMap(), keySelector, valueTransform)
}

fun <K : Entity<K>, V> EntitySequence<K, *>.associateWith(valueTransform: (K) -> V): Map<K, V> {
    return this.associateWithTo(LinkedHashMap(), valueTransform)
}

fun <E : Entity<E>, K, V, M : MutableMap<in K, in V>> EntitySequence<E, *>.associateTo(destination: M, transform: (E) -> Pair<K, V>): M {
    for (item in this) {
        destination += transform(item)
    }
    return destination
}

fun <E : Entity<E>, K, M : MutableMap<in K, in E>> EntitySequence<E, *>.associateByTo(destination: M, keySelector: (E) -> K): M {
    for (item in this) {
        destination.put(keySelector(item), item)
    }
    return destination
}

fun <E : Entity<E>, K, V, M : MutableMap<in K, in V>> EntitySequence<E, *>.associateByTo(destination: M, keySelector: (E) -> K, valueTransform: (E) -> V): M {
    for (item in this) {
        destination.put(keySelector(item), valueTransform(item))
    }
    return destination
}

fun <K : Entity<K>, V, M : MutableMap<in K, in V>> EntitySequence<K, *>.associateWithTo(destination: M, valueTransform: (K) -> V): M {
    for (item in this) {
        destination.put(item, valueTransform(item))
    }
    return destination
}

fun <E : Entity<E>, T : Table<E>> EntitySequence<E, T>.drop(n: Int): EntitySequence<E, T> {
    if (n <= 0) {
        return this
    } else {
        return this.copy(expression = expression.copy(offset = n))
    }
}

fun <E : Entity<E>, T : Table<E>> EntitySequence<E, T>.take(n: Int): EntitySequence<E, T> {
    return this.copy(expression = expression.copy(limit = n))
}