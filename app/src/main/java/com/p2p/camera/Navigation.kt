package com.p2p.camera

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import com.p2p.feature.call.CallScreen
import com.p2p.feature.contacts.ContactsScreen

@Composable
fun MainNavigation() {
    val backStack = remember { mutableStateListOf<Any>(Contacts) }

    when (val currentScreen = backStack.lastOrNull()) {
        is Contacts -> {
            ContactsScreen(
                onDialPeer = { address ->
                    backStack.add(Call(address))
                }
            )
        }
        is Call -> {
            BackHandler(enabled = backStack.size > 1) {
                backStack.removeLast()
            }
            CallScreen(
                peerAddress = currentScreen.peerAddress,
                onCallEnded = {
                    if (backStack.size > 1) {
                        backStack.removeLast()
                    }
                }
            )
        }
    }
}
