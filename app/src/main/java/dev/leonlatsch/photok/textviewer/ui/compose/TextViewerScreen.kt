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

package dev.leonlatsch.photok.textviewer.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.leonlatsch.photok.R
import dev.leonlatsch.photok.navigation.LocalNavigator
import dev.leonlatsch.photok.textviewer.ui.TextViewerUiState
import dev.leonlatsch.photok.textviewer.ui.TextViewerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextViewerScreen(photoUuid: String) {
    val viewModel: TextViewerViewModel = hiltViewModel<TextViewerViewModel, TextViewerViewModel.Factory>(
        creationCallback = { factory -> factory.create(photoUuid) }
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = (uiState as? TextViewerUiState.Content)?.fileName.orEmpty(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = navigator::goBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_back),
                            contentDescription = stringResource(R.string.process_close),
                        )
                    }
                },
            )
        },
    ) { contentPadding ->
        when (val state = uiState) {
            is TextViewerUiState.Loading -> Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            ) {
                CircularProgressIndicator()
            }

            is TextViewerUiState.Error -> Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            ) {
                Text(stringResource(R.string.common_error))
            }

            is TextViewerUiState.Content -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            ) {
                SelectionContainer {
                    Text(
                        text = state.text,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                    )
                }
            }
        }
    }
}
