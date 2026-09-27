package org.olcbox.app.ui.motion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import app.rive.Result as RiveResult
import app.rive.Fit
import app.rive.Rive
import app.rive.RiveFileSource
import app.rive.ViewModelSource
import app.rive.rememberArtboardResult
import app.rive.rememberRiveFile
import app.rive.rememberRiveWorkerOrNull
import app.rive.rememberStateMachineResult
import app.rive.rememberViewModelInstanceResult
import app.rive.runtime.kotlin.core.ViewModel
import org.olcbox.app.sharedui.R
import org.olcbox.app.ui.features.home.ApolloHeroPhase
import kotlin.coroutines.cancellation.CancellationException

internal fun hasNumericApolloRoutePhase(properties: List<ViewModel.Property>): Boolean =
    properties.any { it.name == "phase" && it.type == ViewModel.PropertyDataType.NUMBER }

/** Optional decoration: all connection text and the static route remain in Compose. */
@Composable
internal fun ApolloRiveRouteMotion(phase: ApolloHeroPhase, modifier: Modifier = Modifier): Boolean {
    val error = remember { mutableStateOf<Throwable?>(null) }
    val worker = rememberRiveWorkerOrNull(error) ?: return false
    val fileResult = rememberRiveFile(RiveFileSource.RawRes.from(R.raw.apollo_route_motion), worker)
    if (fileResult !is RiveResult.Success) return false

    val file = fileResult.value
    val phasePropertyValid = produceState<Boolean?>(initialValue = null, file) {
        value = try {
            hasNumericApolloRoutePhase(file.getViewModelProperties("ApolloConnection"))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }.value
    if (phasePropertyValid != true) return false

    val artboardResult = rememberArtboardResult(file, "Apollo Route Motion")
    if (artboardResult !is RiveResult.Success) return false
    val artboard = artboardResult.value
    val stateMachineResult = rememberStateMachineResult(artboard, "ApolloRouteState")
    if (stateMachineResult !is RiveResult.Success) return false
    val stateMachine = stateMachineResult.value

    val instanceResult = rememberViewModelInstanceResult(
        file,
        ViewModelSource.Named("ApolloConnection").defaultInstance()
    )
    if (instanceResult !is RiveResult.Success) return false

    val instance = instanceResult.value
    LaunchedEffect(instance, phase) {
        instance.setNumber("phase", phase.ordinal.toFloat())
    }
    Rive(
        file = file,
        artboard = artboard,
        stateMachine = stateMachine,
        viewModelInstance = instance,
        fit = Fit.Cover(),
        modifier = modifier.clearAndSetSemantics { }
    )
    return true
}
