package com.p2p.camera

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.p2p.core.network.SignalingServer
import com.p2p.core.network.SignalingEvent
import com.p2p.feature.call.CallScreen
import com.p2p.feature.contacts.ContactsScreen
import dagger.hilt.android.EntryPointAccessors

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface NavigationEntryPoint {
    fun signalingServer(): SignalingServer
}

@Composable
fun MainNavigation() {
    val backStack = remember { mutableStateListOf<Any>(Contacts) }

    val context = LocalContext.current
    val server = remember {
        EntryPointAccessors.fromApplication(context.applicationContext, NavigationEntryPoint::class.java)
            .signalingServer()
    }

    LaunchedEffect(server) {
        server.events.collect { event ->
            if (event is SignalingEvent.IncomingOffer) {
                if (backStack.none { it is Call }) {
                    backStack.add(Call(server.remoteHost, isIncoming = true))
                }
            }
        }
    }

    when (val currentScreen = backStack.lastOrNull()) {
        is Contacts -> {
            ContactsScreen(
                onDialPeer = { address ->
                    backStack.add(Call(address, isIncoming = false))
                }
            )
        }
        is Call -> {
            BackHandler(enabled = backStack.size > 1) {
                backStack.removeLast()
            }
            CallScreen(
                peerAddress = currentScreen.peerAddress,
                isIncoming = currentScreen.isIncoming,
                onCallEnded = {
                    if (backStack.size > 1) {
                        backStack.removeLast()
                    }
                }
            )
        }
    }
}
