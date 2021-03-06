/**
 * Copyright (c) 2002-2014 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compiler.v2_1.pipes

import org.neo4j.cypher.internal.compiler.v2_1._
import commands.SortItem
import commands.expressions.Expression
import data.SimpleVal
import symbols._
import collection.mutable.ListBuffer

/*
 * TopPipe is used when a query does a ORDER BY ... LIMIT query. Instead of ordering the whole result set and then
 * returning the matching top results, we only keep the top results in heap, which allows us to release memory earlier
 */
class TopPipe(source: Pipe, sortDescription: List[SortItem], countExpression: Expression)
             (implicit pipeMonitor: PipeMonitor) extends PipeWithSource(source, pipeMonitor) with ExecutionContextComparer {
  protected def internalCreateResults(input:Iterator[ExecutionContext], state: QueryState): Iterator[ExecutionContext] = {
    implicit val s = state
    var result = new ListBuffer[ExecutionContext]()
    var last: Option[ExecutionContext] = None
    val largerThanLast = (ctx: ExecutionContext) => last.forall(s => compareBy(s, ctx, sortDescription)(state))
    var size = 0
    var sorted = false

    if (input.isEmpty)
      Iterator.empty
    else {
      val first = input.next()
      val count = countExpression(first).asInstanceOf[Number].intValue()

      val iter = new HeadAndTail(first, input)
      iter.foreach {
        case ctx =>

          if (size < count) {
            result += ctx
            size += 1

            if (largerThanLast(ctx)) {
              last = Some(ctx)
            }
          } else
            if (!largerThanLast(ctx)) {
              result -= last.get
              result += ctx
              result = result.sortWith((a, b) => compareBy(a, b, sortDescription)(state))
              sorted = true
              last = Some(result.last)
            }
      }
    }

    if (!sorted) {
      result = result.sortWith((a, b) => compareBy(a, b, sortDescription)(state))
    }


    result.toIterator
  }

  def executionPlanDescription =
    source.executionPlanDescription
      .andThen(this, "Top",
        "orderBy" -> SimpleVal.fromIterable(sortDescription),
        "limit" -> SimpleVal.fromStr(countExpression))

  def symbols = source.symbols

  override def isLazy = false
}
