package com.rusyn.wristhook.tile

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.ChipColors
import androidx.wear.protolayout.material.Colors
import androidx.wear.protolayout.material.CompactChip
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture

private const val RESOURCES_VERSION = "2"

/**
 * A one-tap tile: swipe to it on the watch face, tap "Record", and MainActivity
 * launches straight into recording.
 */
class WristHookTileService : TileService() {

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest,
    ): ListenableFuture<TileBuilders.Tile> =
        CallbackToFutureAdapter.getFuture { completer ->
            val deviceParams = requestParams.deviceConfiguration

            val launchRecording = ModifiersBuilders.Clickable.Builder()
                .setId("record")
                .setOnClick(
                    ActionBuilders.LaunchAction.Builder()
                        .setAndroidActivity(
                            ActionBuilders.AndroidActivity.Builder()
                                .setPackageName(packageName)
                                .setClassName("com.rusyn.wristhook.MainActivity")
                                .build()
                        )
                        .build()
                )
                .build()

            // The whole tile is the record button: a full-bleed, primary-coloured
            // surface with a big mic + label, tappable anywhere.
            val content = LayoutElementBuilders.Column.Builder()
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .addContent(
                    LayoutElementBuilders.Image.Builder()
                        .setResourceId("mic")
                        .setWidth(DimensionBuilders.dp(56f))
                        .setHeight(DimensionBuilders.dp(56f))
                        .build()
                )
                .addContent(
                    LayoutElementBuilders.Box.Builder()
                        .setWidth(DimensionBuilders.dp(1f))
                        .setHeight(DimensionBuilders.dp(8f))
                        .build()
                )
                .addContent(
                    Text.Builder(this, getString(com.rusyn.wristhook.R.string.tile_button))
                        .setTypography(Typography.TYPOGRAPHY_TITLE1)
                        .setColor(argb(Colors.DEFAULT.onPrimary))
                        .build()
                )
                .build()

            val layout = LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHeight(DimensionBuilders.expand())
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                .setModifiers(
                    ModifiersBuilders.Modifiers.Builder()
                        .setClickable(launchRecording)
                        .setBackground(
                            ModifiersBuilders.Background.Builder()
                                .setColor(argb(Colors.DEFAULT.primary))
                                .build()
                        )
                        .build()
                )
                .addContent(content)
                .build()

            completer.set(
                TileBuilders.Tile.Builder()
                    .setResourcesVersion(RESOURCES_VERSION)
                    .setTileTimeline(
                        TimelineBuilders.Timeline.Builder()
                            .addTimelineEntry(
                                TimelineBuilders.TimelineEntry.Builder()
                                    .setLayout(
                                        LayoutElementBuilders.Layout.Builder()
                                            .setRoot(layout)
                                            .build()
                                    )
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            "tileRequest"
        }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> =
        CallbackToFutureAdapter.getFuture { completer ->
            completer.set(
                ResourceBuilders.Resources.Builder()
                    .setVersion(RESOURCES_VERSION)
                    .addIdToImageMapping(
                        "mic",
                        ResourceBuilders.ImageResource.Builder()
                            .setAndroidResourceByResId(
                                ResourceBuilders.AndroidImageResourceByResId.Builder()
                                    .setResourceId(com.rusyn.wristhook.R.drawable.ic_mic)
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            "resourcesRequest"
        }
}
