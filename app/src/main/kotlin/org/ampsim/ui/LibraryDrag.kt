package org.ampsim.ui

/**
 * Prefix marking a drag that carries a *new* module type from the Library.
 *
 * The Chain Editor's own reorder drags carry a bare unit id, and both land on
 * the same [org.gnome.gtk.DropTarget]s, so the payload has to say which kind of
 * drop it is: `"library-module:delay"` adds a delay, `"delay"` would be an
 * existing unit whose id happens to be "delay".
 */
const val LIBRARY_DRAG_PREFIX = "library-module:"

/** The drag payload the Library publishes for a module of [type]. */
fun libraryDragPayload(type: String): String = LIBRARY_DRAG_PREFIX + type

/**
 * The module type carried by [payload], or `null` if it isn't a library drag
 * (i.e. it's a chain reorder).
 */
fun libraryDragType(payload: String): String? =
    payload.removePrefix(LIBRARY_DRAG_PREFIX).takeIf { it != payload && it.isNotEmpty() }
