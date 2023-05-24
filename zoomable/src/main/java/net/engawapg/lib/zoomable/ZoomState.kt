/*
 * Copyright 2022 usuiat
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.engawapg.lib.zoomable

import androidx.annotation.FloatRange
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.lang.Float.max
import kotlin.math.abs

/**
 * A state object that manage scale and offset.
 *
 * @param maxScale The maximum scale of the content.
 * @param contentSize Size of content (i.e. image size.) If Zero, the composable layout size will
 * be used as content size.
 * @param velocityDecay The decay animation spec for fling behaviour.
 */
@Stable
class ZoomState(
    @FloatRange(from = 1.0) private val maxScale: Float = 5f,
    private var contentSize: Size = Size.Zero,
    private val velocityDecay: DecayAnimationSpec<Float> = exponentialDecay(),
) {
    init {
        require(maxScale >= 1.0f) { "maxScale must be at least 1.0." }
    }

    private var _scale = Animatable(1f).apply {
        updateBounds(0.9f, maxScale)
    }

    /**
     * The scale of the content.
     */
    val scale: Float
        get() = _scale.value

    private var _offsetX = Animatable(0f)

    /**
     * The horizontal offset of the content.
     */
    val offsetX: Float
        get() = _offsetX.value

    private var _offsetY = Animatable(0f)

    /**
     * The vertical offset of the content.
     */
    val offsetY: Float
        get() = _offsetY.value

    private var layoutSize = Size.Zero

    /**
     * Set composable layout size.
     *
     * Basically This function is called from [Modifier.zoomable] only.
     *
     * @param size The size of composable layout size.
     */
    fun setLayoutSize(size: Size) {
        layoutSize = size
        updateContentSize()
    }

    /**
     * Set the content size.
     *
     * @param size The content size, for example an image size in pixel.
     */
    fun setContentSize(size: Size) {
        contentSize = size
        updateContentSize()
    }

    private var fitContentSize = Size.Zero
    private fun updateContentSize() {
        if (layoutSize == Size.Zero) {
            fitContentSize = Size.Zero
            return
        }

        if (contentSize == Size.Zero) {
            fitContentSize = layoutSize
            return
        }

        val contentAspectRatio = contentSize.width / contentSize.height
        val layoutAspectRatio = layoutSize.width / layoutSize.height

        fitContentSize = if (contentAspectRatio > layoutAspectRatio) {
            contentSize * (layoutSize.width / contentSize.width)
        } else {
            contentSize * (layoutSize.height / contentSize.height)
        }
    }

    /**
     * Reset the scale and the offsets.
     */
    suspend fun reset() = coroutineScope {
        launch { _scale.snapTo(1f) }
        _offsetX.updateBounds(0f, 0f)
        launch { _offsetX.snapTo(0f) }
        _offsetY.updateBounds(0f, 0f)
        launch { _offsetY.snapTo(0f) }
    }

    private var shouldConsumeEvent: Boolean? = null

    internal fun startGesture() {
        shouldConsumeEvent = null
    }

    internal fun canConsumeGesture(pan: Offset, zoom: Float): Boolean {
        return shouldConsumeEvent ?: run {
            var consume = true
            if (zoom == 1f) { // One finger gesture
                if (scale == 1f) {  // Not zoomed
                    consume = false
                } else {
                    val ratio = (abs(pan.x) / abs(pan.y))
                    if (ratio > 3) {   // Horizontal drag
                        if ((pan.x < 0) && (_offsetX.value == _offsetX.lowerBound)) {
                            // Drag R to L when right edge of the content is shown.
                            consume = false
                        }
                        if ((pan.x > 0) && (_offsetX.value == _offsetX.upperBound)) {
                            // Drag L to R when left edge of the content is shown.
                            consume = false
                        }
                    } else if (ratio < 0.33) { // Vertical drag
                        if ((pan.y < 0) && (_offsetY.value == _offsetY.lowerBound)) {
                            // Drag bottom to top when bottom edge of the content is shown.
                            consume = false
                        }
                        if ((pan.y > 0) && (_offsetY.value == _offsetY.upperBound)) {
                            // Drag top to bottom when top edge of the content is shown.
                            consume = false
                        }
                    }
                }
            }
            shouldConsumeEvent = consume
            consume
        }
    }

    private val velocityTracker = VelocityTracker()
    private var shouldFling = true

    internal suspend fun applyGesture(
        pan: Offset,
        zoom: Float,
        position: Offset,
        timeMillis: Long
    ) = coroutineScope {
        val size = fitContentSize * scale
        val newScale = (scale * zoom).coerceIn(0.9f, maxScale)
        val newSize = fitContentSize * newScale
        val deltaWidth = newSize.width - size.width
        val deltaHeight = newSize.height - size.height

        // Position with the origin at the left top corner of the content.
        val xInContent = position.x - offsetX + (size.width - layoutSize.width) * 0.5f
        val yInContent = position.y - offsetY + (size.height - layoutSize.height) * 0.5f
        // Offset to zoom the content around the pinch gesture position.
        val newOffsetX = (deltaWidth * 0.5f) - (deltaWidth * xInContent / size.width)
        val newOffsetY = (deltaHeight * 0.5f) - (deltaHeight * yInContent / size.height)

        val boundX = max((newSize.width - layoutSize.width), 0f) * 0.5f
        _offsetX.updateBounds(-boundX, boundX)
        launch {
            _offsetX.snapTo(offsetX + pan.x + newOffsetX)
        }

        val boundY = max((newSize.height - layoutSize.height), 0f) * 0.5f
        _offsetY.updateBounds(-boundY, boundY)
        launch {
            _offsetY.snapTo(offsetY + pan.y + newOffsetY)
        }

        launch {
            _scale.snapTo(newScale)
        }

        velocityTracker.addPosition(timeMillis, position)

        if (zoom != 1f) {
            shouldFling = false
        }
    }

    internal suspend fun endGesture() = coroutineScope {
        if (shouldFling) {
            val velocity = velocityTracker.calculateVelocity()
            launch {
                _offsetX.animateDecay(velocity.x, velocityDecay)
            }
            launch {
                _offsetY.animateDecay(velocity.y, velocityDecay)
            }
        }
        shouldFling = true

        if (_scale.value < 1f) {
            launch {
                _scale.animateTo(1f)
            }
        }
    }

    suspend fun zoomToOnFitContentCoordinate(
        zoomTo: Offset,
        scale: Float = 3f,
        animationSpec: AnimationSpec<Float> = tween(700),
    ) = coroutineScope {
        val boundX = max((fitContentSize.width * scale - layoutSize.width), 0f) / 2f
        val boundY = max((fitContentSize.height * scale - layoutSize.height), 0f) / 2f

        if (scale > _scale.value) {
            _offsetX.updateBounds(-boundX, boundX)
            _offsetY.updateBounds(-boundY, boundY)
        }

        launch {
            _scale.animateTo(scale, animationSpec)
        }
        // x方向にズーム先が変更された長さを結果として返す
        val fixedRangeXDef = async {
            // 最終的な表示領域を途中ではみ出る場合はzoomToの位置を調整する
            val targetOffsetX = (fitContentSize.width / 2 - zoomTo.x) * scale
            val fixedTargetOffsetX =
                targetOffsetX.coerceIn(minimumValue = -boundX, maximumValue = boundX)
            _offsetX.animateTo(fixedTargetOffsetX, animationSpec)
            return@async fixedTargetOffsetX - targetOffsetX
        }
        // y方向にズーム先が変更された長さを結果として返す
        val fixedRangeYDef = async {
            // 最終的な表示領域を途中ではみ出る場合はzoomToの位置を調整する
            val targetOffsetY = (fitContentSize.height / 2 - zoomTo.y) * scale
            val fixedTargetOffsetY = targetOffsetY
                .coerceIn(minimumValue = -boundY, maximumValue = boundY)
            _offsetY.animateTo(fixedTargetOffsetY, animationSpec)
            return@async fixedTargetOffsetY - targetOffsetY
        }

        val fixedRangeX = fixedRangeXDef.await()
        val fixedRangeY = fixedRangeYDef.await()

        if (scale < _scale.value) {
            _offsetX.updateBounds(-boundX, boundX)
            _offsetY.updateBounds(-boundY, boundY)
        }
        return@coroutineScope Offset(x = fixedRangeX, y = fixedRangeY)
    }

    suspend fun zoomToOnLayoutCoordinate(
        zoomTo: Offset,
        scale: Float = 3f,
        animationSpec: AnimationSpec<Float> = tween(700),
    ) = coroutineScope {
        val boundX = max((fitContentSize.width * scale - layoutSize.width), 0f) / 2f
        val boundY = max((fitContentSize.height * scale - layoutSize.height), 0f) / 2f
        if (scale > _scale.value) {
            _offsetX.updateBounds(-boundX, boundX)
            _offsetY.updateBounds(-boundY, boundY)
        }

        listOf(
            async {
                _scale.animateTo(scale, animationSpec)
            },
            async {
                // 最終的な表示領域をZoom途中ではみ出る場合はzoomToの位置を調整する
                val fixedTargetOffsetX =
                    ((layoutSize.width / 2 - zoomTo.x) * scale)
                        .coerceAtMost(boundX)
                        .coerceAtLeast(-boundX)
                _offsetX.animateTo(fixedTargetOffsetX, animationSpec)
            },
            async {
                // 最終的な表示領域をZoom途中ではみ出る場合はzoomToの位置を調整する
                val fixedTargetOffsetY = ((layoutSize.height / 2 - zoomTo.y) * scale)
                    .coerceAtMost(boundY)
                    .coerceAtLeast(-boundY)
                _offsetY.animateTo(fixedTargetOffsetY, animationSpec)
            },
        ).awaitAll()

        if (scale < _scale.value) {
            _offsetX.updateBounds(-boundX, boundX)
            _offsetY.updateBounds(-boundY, boundY)
        }
    }
}

/**
 * Creates a [ZoomState] that is remembered across compositions.
 *
 * @param maxScale The maximum scale of the content.
 * @param contentSize Size of content (i.e. image size.) If Zero, the composable layout size will
 * be used as content size.
 * @param velocityDecay The decay animation spec for fling behaviour.
 */
@Composable
fun rememberZoomState(
    @FloatRange(from = 1.0) maxScale: Float = 5f,
    contentSize: Size = Size.Zero,
    velocityDecay: DecayAnimationSpec<Float> = exponentialDecay(),
) = remember {
    ZoomState(maxScale, contentSize, velocityDecay)
}
