package com.georgv.audioworkstation.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.foundation.shape.RoundedCornerShape
import com.georgv.audioworkstation.ui.theme.Alphas
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.Dimens

/**
 * Hardware-style track header button: flat surface, muted idle icon, accent + LED when armed.
 */
@Composable
fun TrackActionButton(
    active: Boolean,
    enabled: Boolean,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = Dimens.TrackHeaderButtonSize,
    surfaceColor: Color = AppColors.SurfacePanel,
    backgroundOverride: Color? = null,
    borderColor: Color? = null,
    activeBackgroundColor: Color? = null,
    activeBorderColor: Color? = null,
    activeIconColor: Color? = null,
    idleIconColor: Color = AppColors.Line.copy(alpha = Alphas.TrackActionIconIdle),
    showActiveGlow: Boolean = true,
    showActiveLed: Boolean = true,
    content: @Composable (iconTint: Color) -> Unit,
) {
    val shape = RoundedCornerShape(Dimens.SmallRadius)
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed && enabled) Alphas.TrackActionPressedScale else 1f,
        animationSpec =
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessHigh,
            ),
        label = "trackActionPressScale",
    )

    val backgroundColor =
        when {
            backgroundOverride != null -> backgroundOverride
            active && activeBackgroundColor != null -> activeBackgroundColor
            else -> surfaceColor
        }
    val resolvedBorderColor =
        borderColor
            ?: when {
                active && activeBorderColor != null -> activeBorderColor
                active -> AppColors.Line.copy(alpha = Alphas.TrackActionBorderActive)
                isPressed && enabled -> AppColors.Line.copy(alpha = Alphas.TrackActionBorderPressed)
                else -> AppColors.Line.copy(alpha = Alphas.TrackActionBorderIdle)
            }
    val iconTint =
        when {
            active && activeIconColor != null -> activeIconColor
            active -> accentColor
            else -> idleIconColor
        }

    Box(
        modifier =
            modifier
                .size(size)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                }
                .alpha(if (enabled) 1f else Alphas.Disabled)
                .clip(shape)
                .background(backgroundColor)
                .border(Dimens.Stroke, resolvedBorderColor, shape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                if (active && showActiveGlow) {
                    Modifier.glow(
                        color = accentColor,
                        blurRadius = Dimens.TrackActionIconGlowBlur,
                        cornerRadius = Dimens.SmallRadius,
                        intensity = Alphas.TrackActionIconGlow,
                        layers = 6,
                    )
                } else {
                    Modifier
                },
            contentAlignment = Alignment.Center,
        ) {
            content(iconTint)
        }
        if (active && showActiveLed) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(Dimens.TrackActionLedInset)
                        .size(Dimens.TrackActionLedSize)
                        .clip(CircleShape)
                        .background(accentColor),
            )
        }
    }
}

private val RecordTargetIconSize = 24.dp
/** Filled [FiberManualRecord] circle diameter in its 24dp viewport. */
private val RecordTargetInnerCircleSize = RecordTargetIconSize * 16f / 24f

@Composable
fun RecordTargetButtonIcon(
    active: Boolean,
    tint: Color,
    contentDescription: String,
) {
    Box(modifier = Modifier.size(RecordTargetIconSize), contentAlignment = Alignment.Center) {
        Icon(
            imageVector = Icons.Filled.FiberManualRecord,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(RecordTargetIconSize),
        )
        if (active) {
            Box(
                modifier =
                    Modifier
                        .size(RecordTargetInnerCircleSize)
                        .border(Dimens.Stroke, AppColors.Line, CircleShape),
            )
        }
    }
}
