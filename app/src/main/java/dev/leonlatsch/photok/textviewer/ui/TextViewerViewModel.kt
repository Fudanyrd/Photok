/*
 *   Copyright 2020–2026 Leon Latsch
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package dev.leonlatsch.photok.textviewer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.leonlatsch.photok.io.VaultFileStorage
import dev.leonlatsch.photok.model.repositories.PhotoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val TEXT_VIEWER_PHOTO_UUID = "photoUuid"

@HiltViewModel(assistedFactory = TextViewerViewModel.Factory::class)
class TextViewerViewModel @AssistedInject constructor(
    @Assisted(TEXT_VIEWER_PHOTO_UUID) private val photoUuid: String,
    private val photoRepository: PhotoRepository,
    private val vaultFileStorage: VaultFileStorage,
) : ViewModel() {

    private val _uiState = MutableStateFlow<TextViewerUiState>(TextViewerUiState.Loading)
    val uiState: StateFlow<TextViewerUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            _uiState.value = loadText()
        }
    }

    private suspend fun loadText(): TextViewerUiState = withContext(Dispatchers.IO) {
        try {
            val photo = photoRepository.get(photoUuid)
            val text = vaultFileStorage
                .openEncryptedInput(photo.internalFileName)
                ?.use { it.readBytes().decodeToString() }
                ?: return@withContext TextViewerUiState.Error

            TextViewerUiState.Content(photo.fileName, text)
        } catch (e: Exception) {
            TextViewerUiState.Error
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(@Assisted(TEXT_VIEWER_PHOTO_UUID) photoUuid: String): TextViewerViewModel
    }
}
