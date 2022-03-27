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

@file:Suppress("UNCHECKED_CAST")

package androidapp.byco.util

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData

/** Combine two [LiveData]s and call back if either value changes */
class PairMediatorLiveData<A, B>(
    internal val a: LiveData<A>,
    internal val b: LiveData<B>
) : MediatorLiveData<Pair<A?, B?>>() {
    init {
        addSources(a, b) { update() }
    }

    private fun update() {
        value = a.value to b.value
    }
}

/** Combine three [LiveData]s and call back if either value changes */
class TripleMediatorLiveData<A, B, C>(
    internal val a: LiveData<A>,
    internal val b: LiveData<B>,
    internal val c: LiveData<C>
) : MediatorLiveData<Triple<A?, B?, C?>>() {
    init {
        addSources(a, b, c) { update() }
    }

    private fun update() {
        value = Triple(a.value, b.value, c.value)
    }
}

/** Combine more than three [LiveData]s and call back if either value changes */
class MultiMediatorLiveData(
    internal vararg val a: LiveData<Any>,
) : MediatorLiveData<List<Any?>>() {
    init {
        addSources(*a) { update() }
    }

    private fun update() {
        value = a.map { it.value }
    }
}

/** Combine two [LiveData]s into a [PairMediatorLiveData] */
operator fun <A, B> LiveData<A>.plus(b: LiveData<B>) = PairMediatorLiveData(this, b)

/** Combine three [LiveData]s into a [TripleMediatorLiveData] */
operator fun <A, B, C> PairMediatorLiveData<A, B>.plus(c: LiveData<C>) =
    TripleMediatorLiveData(a, b, c)

/** Combine three [LiveData]s into a [TripleMediatorLiveData] */
operator fun <A, B, C> LiveData<A>.plus(b: PairMediatorLiveData<B, C>) =
    TripleMediatorLiveData(this, b.a, b.b)

/** Combine four [LiveData]s into a [MultiMediatorLiveData] */
operator fun <A, B, C, D> TripleMediatorLiveData<A, B, C>.plus(d: LiveData<D>) =
    MultiMediatorLiveData(
        a as LiveData<Any>,
        b as LiveData<Any>,
        c as LiveData<Any>,
        d as LiveData<Any>
    )

/** Combine four [LiveData]s into a [MultiMediatorLiveData] */
operator fun <A, B, C, D> LiveData<A>.plus(b: TripleMediatorLiveData<B, C, D>) =
    MultiMediatorLiveData(
        this as LiveData<Any>,
        b.a as LiveData<Any>,
        b.b as LiveData<Any>,
        b.c as LiveData<Any>
    )

/** Combine more than four [LiveData]s into a [MultiMediatorLiveData] */
operator fun <A> LiveData<A>.plus(b: MultiMediatorLiveData) =
    MultiMediatorLiveData(this as LiveData<Any>, *b.a)

/** Combine more than four [LiveData]s into a [MultiMediatorLiveData] */
operator fun <Z> MultiMediatorLiveData.plus(z: LiveData<Z>) =
    MultiMediatorLiveData(*a, z as LiveData<Any>)