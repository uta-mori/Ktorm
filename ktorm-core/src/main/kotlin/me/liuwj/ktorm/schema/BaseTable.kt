/*
 * Copyright 2018-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.liuwj.ktorm.schema

import me.liuwj.ktorm.dsl.QueryRowSet
import me.liuwj.ktorm.entity.Entity
import me.liuwj.ktorm.expression.TableExpression
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KClass
import kotlin.reflect.KProperty
import kotlin.reflect.jvm.jvmErasure

/**
 * Base class of Ktorm's table objects, represents relational tables in the database.
 *
 * This class provides the basic ability of table and column definition but doesn't support any binding mechanisms.
 * If you need the binding support to [Entity] interfaces, use [Table] instead.
 *
 * There is an abstract function [doCreateEntity]. Subclasses should implement this function, creating an entity object
 * from the result set returned by a query, using the binding rules defined by themselves. Here, the type of the entity
 * object could be an interface extending from [Entity], or a data class, POJO, or any kind of classes.
 *
 * Here is an example defining an entity as data class. The full documentation can be found at:
 * https://ktorm.liuwj.me/en/define-entities-as-any-kind-of-classes.html
 *
 * ```kotlin
 * data class Staff(
 *     val id: Int,
 *     val name: String,
 *     val job: String,
 *     val hireDate: LocalDate
 * )
 * object Staffs : BaseTable<Staff>("t_employee") {
 *     val id by int("id").primaryKey()
 *     val name by varchar("name")
 *     val job by varchar("job")
 *     val hireDate by date("hire_date")
 *     override fun doCreateEntity(row: QueryRowSet, withReferences: Boolean) = Staff(
 *         id = row[id] ?: 0,
 *         name = row[name].orEmpty(),
 *         job = row[job].orEmpty(),
 *         hireDate = row[hireDate] ?: LocalDate.now()
 *     )
 * }
 * ```
 *
 * @since 2.5
 */
@Suppress("CanBePrimaryConstructorProperty", "UNCHECKED_CAST")
abstract class BaseTable<E : Any>(
    tableName: String,
    alias: String? = null,
    entityClass: KClass<E>? = null
) : TypeReference<E>() {

    private val _refCounter = AtomicInteger()
    private val _columns = LinkedHashMap<String, Column<*>>()
    private var _primaryKeyName: String? = null

    /**
     * The table's name.
     */
    val tableName: String = tableName

    /**
     * The table's alias.
     */
    val alias: String? = alias

    /**
     * The entity class this table is bound to.
     */
    val entityClass: KClass<E>? =
        (entityClass ?: referencedKotlinType.jvmErasure as KClass<E>).takeIf { it != Nothing::class }

    /**
     * Return all columns of the table.
     */
    val columns: List<Column<*>> get() = _columns.values.toList()

    /**
     * The primary key column of this table.
     */
    val primaryKey: Column<*>? get() = _primaryKeyName?.let { this[it] }

    /**
     * Obtain a column from this table by the name.
     */
    operator fun get(name: String): Column<*> {
        return _columns[name] ?: throw NoSuchElementException(name)
    }

    /**
     * Register a column to this table with the given [name] and [sqlType].
     *
     * This function returns a [ColumnRegistration], we can perform more modifications to the registered column by
     * calling the returned object, such as configure a binding, mark it as the primary key, and so on.
     */
    fun <C : Any> registerColumn(name: String, sqlType: SqlType<C>): ColumnRegistration<C> {
        if (name in _columns) {
            throw IllegalArgumentException("Duplicate column name: $name")
        }

        _columns[name] = Column(this, name, sqlType = sqlType)
        return ColumnRegistration(name)
    }

    /**
     * Mark the registered column as the primary key.
     */
    fun <C : Any> ColumnRegistration<C>.primaryKey(): ColumnRegistration<C> {
        if (_primaryKeyName != null) {
            throw IllegalStateException(
                "Column '$_primaryKeyName' has been configured as the primary key, so you cannot configure another one."
            )
        }

        _primaryKeyName = columnName
        return this
    }

    /**
     * Transform the registered column's [SqlType] to another. The transformed [SqlType] has the same `typeCode` and
     * `typeName` as the underlying one, and performs the specific transformations on column values.
     *
     * This function enables a user-friendly syntax to extend more data types. For example, the following code defines
     * a column of type `Column<UserRole>`, based on the existing column definition function [int]:
     *
     * ```kotlin
     * val role by int("role").transform({ UserRole.fromCode(it) }, { it.code })
     * ```
     *
     * @param fromUnderlyingValue a function that transforms a value of underlying type to the user's type.
     * @param toUnderlyingValue a function that transforms a value of user's type the to the underlying type.
     * @return this column registration with its type changed to [R].
     * @see SqlType.transform
     */
    fun <C : Any, R : Any> ColumnRegistration<C>.transform(
        fromUnderlyingValue: (C) -> R,
        toUnderlyingValue: (R) -> C
    ): ColumnRegistration<R> {
        val column = getColumn()
        if (column.binding != null || column.extraBindings.isNotEmpty()) {
            throw IllegalStateException("Cannot transform a column after its bindings are configured.")
        }

        val transformedType = column.sqlType.transform(fromUnderlyingValue, toUnderlyingValue)
        _columns[columnName] = Column(column.table, column.name, sqlType = transformedType)
        return this as ColumnRegistration<R>
    }

    /**
     * Wrap a new registered column, providing more operations, such as configure a binding, mark it as
     * the primary key, and so on.
     *
     * This class implements the [ReadOnlyProperty] interface, so it can be used as a property delegate,
     * and the delegate returns the registered column. For example:
     *
     * ```kotlin
     * val foo by registerColumn("foo", IntSqlType)
     * ```
     *
     * Here, the `registerColumn` function returns a `ColumnRegistration<Int>` and the `foo` property's
     * type is `Column<Int>`.
     */
    inner class ColumnRegistration<C : Any>(val columnName: String) : ReadOnlyProperty<BaseTable<E>, Column<C>> {

        /**
         * Return the registered column.
         */
        override operator fun getValue(thisRef: BaseTable<E>, property: KProperty<*>): Column<C> {
            check(thisRef === this@BaseTable)
            return getColumn()
        }

        /**
         * Return the registered column.
         */
        fun getColumn(): Column<C> {
            val column = _columns[columnName] ?: throw NoSuchElementException(columnName)
            return column as Column<C>
        }

        /**
         * Configure the binding of the registered column. Note that this function is only used internally by the Ktorm
         * library and its extension modules. Others should not use this function directly.
         */
        @PublishedApi
        internal fun doBindInternal(binding: ColumnBinding): ColumnRegistration<C> {
            for (column in _columns.values) {
                val hasConflict = when (binding) {
                    is NestedBinding -> column.allBindings
                        .filterIsInstance<NestedBinding>()
                        .any { it.properties == binding.properties }
                    is ReferenceBinding -> column.allBindings
                        .filterIsInstance<ReferenceBinding>()
                        .filter { it.referenceTable.tableName == binding.referenceTable.tableName }
                        .any { it.onProperty == binding.onProperty }
                }

                if (hasConflict) {
                    throw IllegalStateException(
                        "Column '$columnName' and '${column.name}' have the same bindings. Please check your code."
                    )
                }
            }

            val b = when (binding) {
                is NestedBinding -> binding
                is ReferenceBinding -> {
                    checkCircularReference(binding.referenceTable)
                    ReferenceBinding(copyReferenceTable(binding.referenceTable), binding.onProperty)
                }
            }

            val column = _columns[columnName] ?: throw NoSuchElementException(columnName)

            if (column.binding == null) {
                _columns[columnName] = column.copy(binding = b)
            } else {
                _columns[columnName] = column.copy(extraBindings = column.extraBindings + b)
            }

            return this
        }

        private fun checkCircularReference(root: BaseTable<*>, stack: LinkedList<String> = LinkedList()) {
            stack.push(root.tableName)

            if (tableName == root.tableName) {
                val msg = "Circular reference detected, current table: %s, reference route: %s"
                throw IllegalArgumentException(msg.format(tableName, stack.asReversed()))
            }

            for (column in root.columns) {
                val ref = column.referenceTable
                if (ref != null) {
                    checkCircularReference(ref, stack)
                }
            }

            stack.pop()
        }
    }

    /**
     * Return a new-created table object with all properties (including the table name and columns and so on) being
     * copied from this table, but applying a new alias given by the parameter.
     *
     * Usually, table objects are defined as Kotlin singleton objects or subclasses extending from [Table]. But limited
     * to the Kotlin language, although the default implementation of this function can create a copied table object
     * with a specific alias, it's return type cannot be the same as the caller's type but only [Table].
     *
     * So we recommend that if we need to use table aliases, please don't define tables as Kotlin's singleton objects,
     * please use classes instead, and override this [aliased] function to return the same type as the concrete table
     * classes.
     *
     * More details can be found on our website: https://ktorm.liuwj.me/en/joining.html#Self-Joining-amp-Table-Aliases
     */
    open fun aliased(alias: String): BaseTable<E> {
        throw UnsupportedOperationException("The function 'aliased' is not supported by $javaClass")
    }

    /**
     * Copy column definitions from [src] to this table.
     */
    protected fun copyDefinitionsFrom(src: BaseTable<*>) {
        rewriteDefinitions(src.columns, src._primaryKeyName, copyReferences = true)
    }

    private fun rewriteDefinitions(columns: List<Column<*>>, primaryKeyName: String?, copyReferences: Boolean) {
        _primaryKeyName = primaryKeyName
        _columns.clear()

        if (copyReferences) {
            _refCounter.set(0)
        }

        for (column in columns) {
            val binding = column.binding?.let { if (copyReferences) copyBinding(it) else it }
            val extraBindings = column.extraBindings.map { if (copyReferences) copyBinding(it) else it }
            _columns[column.name] = column.copy(table = this, binding = binding, extraBindings = extraBindings)
        }
    }

    private fun copyReferenceTable(table: BaseTable<*>): BaseTable<*> {
        val copy = table.aliased("_ref${_refCounter.getAndIncrement()}")

        val columns = copy.columns.map { column ->
            val binding = column.binding?.let { copyBinding(it) }
            val extraBindings = column.extraBindings.map { copyBinding(it) }
            column.copy(binding = binding, extraBindings = extraBindings)
        }

        copy.rewriteDefinitions(columns, copy._primaryKeyName, copyReferences = false)
        return copy
    }

    private fun copyBinding(binding: ColumnBinding): ColumnBinding {
        if (binding is ReferenceBinding) {
            return binding.copy(referenceTable = copyReferenceTable(binding.referenceTable))
        } else {
            return binding
        }
    }

    /**
     * Create an entity object from the specific row of query results. This function uses the binding configurations
     * of the table, filling column values into corresponding properties of the returned entity.
     *
     * If the [withReferences] flag is set to true and there are any reference bindings to other tables, this function
     * will create the referenced entity objects by recursively calling [createEntity] itself.
     *
     * Otherwise if the [withReferences] flag is set to false, it will threat all reference bindings as nested bindings
     * to the referenced entities' primary keys. For example the binding `c.references(Departments) { it.department }`,
     * it is equivalent to `c.bindTo { it.department.id }` in this case, that avoids unnecessary object creations
     * and some exceptions raised by conflict column names.
     */
    fun createEntity(row: QueryRowSet, withReferences: Boolean = true): E {
        val entity = doCreateEntity(row, withReferences)

        val logger = row.query.database.logger
        if (logger.isTraceEnabled()) {
            logger.trace("Entity: $entity")
        }

        return entity
    }

    /**
     * Create an entity object from the specific row without obtaining referenced entities' data automatically.
     *
     * Similar to [createEntity], this function uses the binding configurations of this table object, filling
     * columns' values into corresponding entities' properties. But differently, it treats all reference bindings
     * as nested bindings to the referenced entities’ primary keys.
     *
     * For example the binding `c.references(Departments) { it.department }`, it is equivalent to
     * `c.bindTo { it.department.id }` in this case, that avoids unnecessary object creations and
     * some exceptions raised by conflict column names.
     */
    @Deprecated(
        message = "This function will be removed in the future. Use createEntity(row, withReferences = false) instead.",
        replaceWith = ReplaceWith("createEntity(row, withReferences = false)")
    )
    fun createEntityWithoutReferences(row: QueryRowSet): E {
        return createEntity(row, withReferences = false)
    }

    /**
     * Create an entity object from the specific row of query results.
     *
     * This function is called by [createEntity] and [createEntityWithoutReferences]. Subclasses should override it
     * and implement the actual logic of retrieving an entity object from the query results.
     */
    protected abstract fun doCreateEntity(row: QueryRowSet, withReferences: Boolean): E

    /**
     * Convert this table to a [TableExpression].
     */
    fun asExpression(): TableExpression {
        return TableExpression(tableName, alias)
    }

    /**
     * Return a string representation of this table.
     */
    override fun toString(): String {
        if (alias == null) {
            return "table $tableName"
        } else {
            return "table $tableName $alias"
        }
    }

    /**
     * Indicates whether some other object is "equal to" this table.
     * Two tables are equal only if they are the same instance.
     */
    final override fun equals(other: Any?): Boolean {
        return this === other
    }

    /**
     * Return a hash code value for this table.
     */
    final override fun hashCode(): Int {
        return System.identityHashCode(this)
    }
}
