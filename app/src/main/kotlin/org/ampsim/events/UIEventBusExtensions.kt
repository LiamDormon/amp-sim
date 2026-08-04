package org.ampsim.events

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance

/** Only events of type [T] published on this bus. */
inline fun <reified T : UIEvent> UIEventBus.eventsOfType(): Flow<T> = events.filterIsInstance()

/** Only [UIEvent.ChainModified] events. */
fun UIEventBus.chainModified(): Flow<UIEvent.ChainModified> = eventsOfType()

/** Only [UIEvent.PresetLoaded] events. */
fun UIEventBus.presetLoaded(): Flow<UIEvent.PresetLoaded> = eventsOfType()

/** Only [UIEvent.PresetSaved] events. */
fun UIEventBus.presetSaved(): Flow<UIEvent.PresetSaved> = eventsOfType()

/** Only [UIEvent.ParameterChanged] events. */
fun UIEventBus.parameterChanged(): Flow<UIEvent.ParameterChanged> = eventsOfType()

/** Only [UIEvent.AudioStatusChanged] events. */
fun UIEventBus.audioStatusChanged(): Flow<UIEvent.AudioStatusChanged> = eventsOfType()

/** Only [UIEvent.UnitAdded] events. */
fun UIEventBus.unitAdded(): Flow<UIEvent.UnitAdded> = eventsOfType()

/** Only [UIEvent.UnitRemoved] events. */
fun UIEventBus.unitRemoved(): Flow<UIEvent.UnitRemoved> = eventsOfType()

/** Only [UIEvent.ErrorOccurred] events. */
fun UIEventBus.errorOccurred(): Flow<UIEvent.ErrorOccurred> = eventsOfType()
