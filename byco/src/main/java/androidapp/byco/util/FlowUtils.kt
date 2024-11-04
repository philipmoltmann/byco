/*
 * Copyright 2024 Google LLC
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

package androidapp.byco.util

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val KEEP_STATE_WHILE_UNSUBSCRIBED = 2000L

fun <T> Flow<T>.stateIn(coroutineScope: CoroutineScope, initial: T): StateFlow<T> {
    return this.stateIn(
        coroutineScope,
        SharingStarted.WhileSubscribed(KEEP_STATE_WHILE_UNSUBSCRIBED),
        initial
    )
}

context(Repository)
fun <T> Flow<T>.stateIn(initial: T): StateFlow<T> {
    return this.stateIn(
        repositoryScope,
        initial
    )
}

context(ViewModel)
fun <T> Flow<T>.stateIn(initial: T): StateFlow<T> {
    return this.stateIn(
        viewModelScope,
        initial
    )
}

/** [Flow] filter that emits only if a the flow's value stays constant with a certain time. */
fun <T> Flow<T>.whenNotChangedFor(
    timeout: Duration,
    isEqual: (T, T) -> Boolean = { a, b -> a == b }
) = channelFlow {
    var timeoutJob: Job? = null
    var oldValue = emptyList<T>()

    this@whenNotChangedFor.collect { newValue ->
        if (oldValue.isEmpty() || !isEqual(oldValue[0], newValue)) {
            oldValue = listOf(newValue)
            timeoutJob?.cancel()

            timeoutJob = coroutineScope {
                launch {
                    delay(timeout)

                    oldValue = emptyList()
                    send(newValue)
                }
            }
        }
    }
}

/**
 * If a [Flow] produces a [Set] of `X`s and for each `X` another [Flow] maps `X -> Y` this
 * class produces a [Map] from `X` to `Y`.
 *
 * E.g. if `outer` produces list of soccer players and `getNestedLiveData` produces a [Flow]
 * that maps a soccer player to her/his team this class will produce a mapping of all the soccer
 * players to their teams.
 *
 * ```kotlin
 * val soccerPlayers = flow<Set<SoccerPlayer>> { ... }
 * fun getTeam(player: SoccerPlayer) = flow<Team> { ... }
 *
 * val soccerPlayerToTeamMapping: Flow<Map<SoccerPlayer, Team>> =
 *     soccerPlayers.nestedFlowNonNull { player -> getTeam(player) }
 * ```
 */
inline fun <X, reified Y> Flow<Set<X>>.nestedFlowToMapFlow(
    crossinline getNestedFlow: (X) -> Flow<Y>
) = channelFlow {
    var nestedJob: Job? = null

    collect { xs ->
        nestedJob?.cancelAndJoin()

        nestedJob = launch {
            combine(xs.map { x -> x to getNestedFlow(x) }
                .map { (x, flowY) -> flowY.map { y -> x to y } }) { xtoys ->
                xtoys.toMap()
            }.collect { send(it) }
        }
    }
}

/** Flow that updates every second. */
fun everySecond() = flow {
    var counter = 0L

    while (coroutineContext.isActive) {
        emit(counter)
        counter++
        delay(1.0.seconds)
    }
}
