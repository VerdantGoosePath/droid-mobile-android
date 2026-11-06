package com.droidmobile.client.backend

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.json.JSONObject

class DroidViewModel(val repository: DroidRepository) : ViewModel() {
    val state = repository.state
    fun connect(url: String, key: String) = viewModelScope.launch { repository.connect(url, key) }
    fun disconnect() = viewModelScope.launch { repository.disconnect() }
    fun refreshSessions() = viewModelScope.launch { repository.refreshSessions() }
    fun openSession(id: String) = viewModelScope.launch { repository.openSession(id) }
    fun refreshMessages() = viewModelScope.launch { repository.refreshMessages() }
    fun send(text: String) = viewModelScope.launch { repository.send(text) }
    fun fetch(section: String, op: String, args: JSONObject = JSONObject()) =
        viewModelScope.launch { repository.fetchSection(section, op, args) }
    fun action(op: String, args: JSONObject = JSONObject()) =
        viewModelScope.launch { repository.invoke(op, args) }
    fun permission(answer: JSONObject) = viewModelScope.launch { repository.respondPermission(answer) }
    fun question(answer: JSONObject) = viewModelScope.launch { repository.respondQuestion(answer) }
    override fun onCleared() { repository.close() }
}
