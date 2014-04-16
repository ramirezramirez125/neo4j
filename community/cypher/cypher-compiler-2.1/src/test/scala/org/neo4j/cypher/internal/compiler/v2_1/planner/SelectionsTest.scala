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
package org.neo4j.cypher.internal.compiler.v2_1.planner

import org.neo4j.cypher.internal.commons.CypherFunSuite
import org.neo4j.cypher.internal.compiler.v2_1.ast._
import org.neo4j.cypher.internal.compiler.v2_1.{InputPosition, DummyPosition}
import org.neo4j.cypher.internal.compiler.v2_1.ast.LabelName
import org.neo4j.cypher.internal.compiler.v2_1.ast.Equals
import org.neo4j.cypher.internal.compiler.v2_1.ast.Identifier
import org.neo4j.cypher.internal.compiler.v2_1.ast.HasLabels
import org.neo4j.cypher.internal.compiler.v2_1.planner.logical.plans.IdName

class SelectionsTest extends CypherFunSuite with LogicalPlanningTestSupport {

  val aIsPerson: HasLabels = identHasLabel("a", "Person")
  val bIsAnimal: HasLabels = identHasLabel("b", "Animal")
  val compareTwoNodes: Equals = compareBothSides("a", "b")

  test("can flat predicates to a sequence") {
    val selections = Selections(Seq(idNames("a") -> aIsPerson))

    selections.flatPredicates should equal(Seq(aIsPerson))
  }

  test("can flat empty predicates to an empty sequence") {
    Selections().flatPredicates should equal(Seq())
  }

  test("should be able to sense that predicates are not covered") {
    val selections = Selections(Seq(
      idNames("a") -> aIsPerson,
      idNames("b") -> bIsAnimal
    ))

    selections.coveredBy(Seq(aIsPerson)) should be(false)
  }

  test("should be able to tell when all predicates are covered") {
    val selections = Selections(Seq(
      idNames("a") -> aIsPerson
    ))

    selections.coveredBy(Seq(aIsPerson)) should be(true)
  }

  test("can extract HasLabels Predicates") {
    val selections = Selections(Seq(
      idNames("a") -> aIsPerson,
      idNames("a") -> aIsPerson,
      idNames("b") -> bIsAnimal,
      idNames("c") -> Equals(Identifier("c")_, SignedIntegerLiteral("42")_)_
    ))

    selections.labelPredicates should equal(Map(
      IdName("a") -> Set(aIsPerson),
      IdName("b") -> Set(bIsAnimal)
    ))
  }

  test("can find predicates given covered ids") {
    val a = idNames("a")
    val b = idNames("b")

    val selections = Selections(Seq(
      a -> aIsPerson,
      b -> bIsAnimal
    ))

    selections.predicatesGiven(a) should equal(Seq(aIsPerson))
  }

  test("returns no predicates if no ids are covered") {
    val a = idNames("a")
    val b = idNames("b")

    val selections = Selections(Seq(
      a -> aIsPerson,
      b -> bIsAnimal
    ))

    selections.predicatesGiven(Set.empty) should equal(Seq.empty)
  }

  test("does not take on a predicate if it is only half covered") {
    val aAndB = idNames("a", "b")
    val a = Set(aAndB.head)

    val selections = Selections(Seq(
      aAndB -> compareTwoNodes
    ))

    selections.predicatesGiven(a) should equal(Seq.empty)
  }

  private def idNames(names: String*) = names.map(IdName(_)).toSet

  private def identHasLabel(name: String, labelName: String): HasLabels =
    HasLabels(Identifier(name)_, Seq(LabelName(labelName)()_))_

  private def compareBothSides(left: String, right: String): Equals = {
    val l: Identifier = Identifier(left)_
    val r: Identifier = Identifier(right)_
    val propName1 = PropertyKeyName("prop1")(None)_
    val leftProp = Property(l, propName1)_
    val rightProp = Property(r, propName1)_
    Equals(leftProp, rightProp)_
  }
}
