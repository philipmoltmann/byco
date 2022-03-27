/*
 * Copyright 2022 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidapp.byco.data

import androidapp.byco.data.OsmDataProvider.HighwayType.GENERATED
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(minSdk = 28, maxSdk = 28)
class WayTest {
    @Test
    fun startAndEndAreNeighborsInClosedWay() {
        val n1 = Node(latitude = 0.0, longitude = 0.0)
        val n2 = Node(latitude = 0.0, longitude = 0.0)
        val n3 = Node(latitude = 0.0, longitude = 0.0)
        val n4 = Node(latitude = 0.0, longitude = 0.0)

        val way = Way(isOpen = false, highway = GENERATED, nodesArray = arrayOf(n1, n2, n3, n4))
        way.nodesArray.forEach { it.mutableWays += way }

        assertThat(way.areNeighbors(n1, n2)).isTrue()
        assertThat(way.areNeighbors(n2, n3)).isTrue()
        assertThat(way.areNeighbors(n3, n4)).isTrue()
        assertThat(way.areNeighbors(n4, n1)).isTrue()

        assertThat(way.areNeighbors(n2, n1)).isTrue()
        assertThat(way.areNeighbors(n3, n2)).isTrue()
        assertThat(way.areNeighbors(n4, n3)).isTrue()
        assertThat(way.areNeighbors(n1, n4)).isTrue()

        assertThat(way.areNeighbors(n1, n3)).isFalse()
        assertThat(way.areNeighbors(n2, n4)).isFalse()
        assertThat(way.areNeighbors(n3, n1)).isFalse()
        assertThat(way.areNeighbors(n4, n2)).isFalse()

        assertThat(way.areNeighbors(n1, n1)).isFalse()
        assertThat(way.areNeighbors(n2, n2)).isFalse()
        assertThat(way.areNeighbors(n3, n3)).isFalse()
        assertThat(way.areNeighbors(n4, n4)).isFalse()

        assertThat(way.getNeighborsOf(n1).toList()).containsExactly(n2, n4)
        assertThat(way.getNeighborsOf(n2).toList()).containsExactly(n1, n3)
        assertThat(way.getNeighborsOf(n3).toList()).containsExactly(n2, n4)
        assertThat(way.getNeighborsOf(n4).toList()).containsExactly(n1, n3)

        assertThat(n1.getWayTo(n2)).isEqualTo(way)
        assertThat(n2.getWayTo(n3)).isEqualTo(way)
        assertThat(n3.getWayTo(n4)).isEqualTo(way)
        assertThat(n4.getWayTo(n1)).isEqualTo(way)

        assertThat(n1.getWayTo(n4)).isEqualTo(way)
        assertThat(n2.getWayTo(n1)).isEqualTo(way)
        assertThat(n3.getWayTo(n2)).isEqualTo(way)
        assertThat(n4.getWayTo(n3)).isEqualTo(way)

        assertThat(n1.getWayTo(n1)).isNull()
        assertThat(n2.getWayTo(n2)).isNull()
        assertThat(n3.getWayTo(n3)).isNull()
        assertThat(n4.getWayTo(n4)).isNull()

        assertThat(n1.getWayTo(n3)).isNull()
        assertThat(n2.getWayTo(n4)).isNull()
        assertThat(n3.getWayTo(n1)).isNull()
        assertThat(n4.getWayTo(n2)).isNull()
    }

    @Test
    fun allAreNeighborsInShortClosedWay() {
        val n1 = Node(latitude = 0.0, longitude = 0.0)
        val n2 = Node(latitude = 0.0, longitude = 0.0)

        val way = Way(isOpen = false, highway = GENERATED, nodesArray = arrayOf(n1, n2))
        way.nodesArray.forEach { it.mutableWays += way }

        assertThat(way.areNeighbors(n1, n2)).isTrue()
        assertThat(way.areNeighbors(n2, n1)).isTrue()

        assertThat(way.areNeighbors(n1, n1)).isFalse()
        assertThat(way.areNeighbors(n2, n2)).isFalse()

        assertThat(way.getNeighborsOf(n1).toList()).containsExactly(n2)
        assertThat(way.getNeighborsOf(n2).toList()).containsExactly(n1)

        assertThat(n1.getWayTo(n2)).isEqualTo(way)
        assertThat(n2.getWayTo(n1)).isEqualTo(way)

        assertThat(n1.getWayTo(n1)).isNull()
        assertThat(n2.getWayTo(n2)).isNull()
    }

    @Test
    fun noneAreNotNeighborsInSingleNodeClosedWay() {
        val n1 = Node(latitude = 0.0, longitude = 0.0)

        val way = Way(isOpen = false, highway = GENERATED, nodesArray = arrayOf(n1))
        way.nodesArray.forEach { it.mutableWays += way }

        assertThat(way.areNeighbors(n1, n1)).isFalse()

        assertThat(way.getNeighborsOf(n1).toList()).containsExactly()

        assertThat(n1.getWayTo(n1)).isNull()
    }

    @Test
    fun startAndEndAreNotNeighborsInOpenWay() {
        val n1 = Node(latitude = 0.0, longitude = 0.0)
        val n2 = Node(latitude = 0.0, longitude = 0.0)
        val n3 = Node(latitude = 0.0, longitude = 0.0)
        val n4 = Node(latitude = 0.0, longitude = 0.0)

        val way = Way(isOpen = true, highway = GENERATED, nodesArray = arrayOf(n1, n2, n3, n4))
        way.nodesArray.forEach { it.mutableWays += way }

        assertThat(way.areNeighbors(n1, n2)).isTrue()
        assertThat(way.areNeighbors(n2, n3)).isTrue()
        assertThat(way.areNeighbors(n3, n4)).isTrue()
        assertThat(way.areNeighbors(n4, n1)).isFalse()

        assertThat(way.areNeighbors(n2, n1)).isTrue()
        assertThat(way.areNeighbors(n3, n2)).isTrue()
        assertThat(way.areNeighbors(n4, n3)).isTrue()
        assertThat(way.areNeighbors(n1, n4)).isFalse()

        assertThat(way.areNeighbors(n1, n3)).isFalse()
        assertThat(way.areNeighbors(n2, n4)).isFalse()
        assertThat(way.areNeighbors(n3, n1)).isFalse()
        assertThat(way.areNeighbors(n4, n2)).isFalse()

        assertThat(way.areNeighbors(n1, n1)).isFalse()
        assertThat(way.areNeighbors(n2, n2)).isFalse()
        assertThat(way.areNeighbors(n3, n3)).isFalse()
        assertThat(way.areNeighbors(n4, n4)).isFalse()

        assertThat(way.getNeighborsOf(n1).toList()).containsExactly(n2)
        assertThat(way.getNeighborsOf(n2).toList()).containsExactly(n1, n3)
        assertThat(way.getNeighborsOf(n3).toList()).containsExactly(n2, n4)
        assertThat(way.getNeighborsOf(n4).toList()).containsExactly(n3)

        assertThat(n1.getWayTo(n2)).isEqualTo(way)
        assertThat(n2.getWayTo(n3)).isEqualTo(way)
        assertThat(n3.getWayTo(n4)).isEqualTo(way)
        assertThat(n4.getWayTo(n1)).isNull()

        assertThat(n1.getWayTo(n4)).isNull()
        assertThat(n2.getWayTo(n1)).isEqualTo(way)
        assertThat(n3.getWayTo(n2)).isEqualTo(way)
        assertThat(n4.getWayTo(n3)).isEqualTo(way)

        assertThat(n1.getWayTo(n3)).isNull()
        assertThat(n2.getWayTo(n4)).isNull()
        assertThat(n3.getWayTo(n1)).isNull()
        assertThat(n4.getWayTo(n2)).isNull()
    }

    @Test
    fun allAreNotNeighborsInShortOpenWay() {
        val n1 = Node(latitude = 0.0, longitude = 0.0)
        val n2 = Node(latitude = 0.0, longitude = 0.0)

        val way = Way(isOpen = true, highway = GENERATED, nodesArray = arrayOf(n1, n2))
        way.nodesArray.forEach { it.mutableWays += way }

        assertThat(way.areNeighbors(n1, n2)).isTrue()
        assertThat(way.areNeighbors(n2, n1)).isTrue()

        assertThat(way.areNeighbors(n1, n1)).isFalse()
        assertThat(way.areNeighbors(n2, n2)).isFalse()

        assertThat(way.getNeighborsOf(n1).toList()).containsExactly(n2)
        assertThat(way.getNeighborsOf(n2).toList()).containsExactly(n1)

        assertThat(n1.getWayTo(n2)).isEqualTo(way)
        assertThat(n2.getWayTo(n1)).isEqualTo(way)

        assertThat(n1.getWayTo(n1)).isNull()
        assertThat(n2.getWayTo(n2)).isNull()
    }

    @Test
    fun noneAreNotNeighborsInSingleNodeOpenWay() {
        val n1 = Node(latitude = 0.0, longitude = 0.0)

        val way = Way(isOpen = true, highway = GENERATED, nodesArray = arrayOf(n1))
        way.nodesArray.forEach { it.mutableWays += way }

        assertThat(way.areNeighbors(n1, n1)).isFalse()

        assertThat(way.getNeighborsOf(n1).toList()).isEmpty()

        assertThat(n1.getWayTo(n1)).isNull()
    }

    @Test
    fun mostNextNodesAreNeighborsInOpenOneway() {
        val n1 = Node(latitude = 0.0, longitude = 0.0)
        val n2 = Node(latitude = 0.0, longitude = 0.0)
        val n3 = Node(latitude = 0.0, longitude = 0.0)

        val way = Way(
            isOpen = true,
            isOneway = true,
            highway = GENERATED,
            nodesArray = arrayOf(n1, n2, n3)
        )

        assertThat(way.areNeighbors(n1, n2)).isTrue()
        assertThat(way.getNeighborsOf(n1).toList()).containsExactly(n2)
        assertThat(way.areNeighbors(n2, n3)).isTrue()
        assertThat(way.getNeighborsOf(n2).toList()).containsExactly(n3)
        assertThat(way.areNeighbors(n3, n1)).isFalse()
        assertThat(way.getNeighborsOf(n3).toList()).isEmpty()

        assertThat(way.areNeighbors(n1, n3)).isFalse()
        assertThat(way.areNeighbors(n2, n1)).isFalse()
        assertThat(way.areNeighbors(n3, n2)).isFalse()
    }

    @Test
    fun nextNodesAreNeighborsInClosedOneway() {
        val n1 = Node(latitude = 0.0, longitude = 0.0)
        val n2 = Node(latitude = 0.0, longitude = 0.0)
        val n3 = Node(latitude = 0.0, longitude = 0.0)

        val way = Way(
            isOpen = false,
            isOneway = true,
            highway = GENERATED,
            nodesArray = arrayOf(n1, n2, n3)
        )

        assertThat(way.areNeighbors(n1, n2)).isTrue()
        assertThat(way.getNeighborsOf(n1).toList()).containsExactly(n2)
        assertThat(way.areNeighbors(n2, n3)).isTrue()
        assertThat(way.getNeighborsOf(n2).toList()).containsExactly(n3)
        assertThat(way.areNeighbors(n3, n1)).isTrue()
        assertThat(way.getNeighborsOf(n3).toList()).containsExactly(n1)

        assertThat(way.areNeighbors(n1, n3)).isFalse()
        assertThat(way.areNeighbors(n2, n1)).isFalse()
        assertThat(way.areNeighbors(n3, n2)).isFalse()
    }
}