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

package dev.leonlatsch.photok.backup.cloud.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.leonlatsch.photok.backup.cloud.data.auth.CloudTokenStore
import dev.leonlatsch.photok.backup.cloud.data.auth.GoogleCloudAuth
import dev.leonlatsch.photok.backup.cloud.data.auth.KeystoreCloudTokenStore
import dev.leonlatsch.photok.backup.cloud.data.auth.KeystoreTokenCipher
import dev.leonlatsch.photok.backup.cloud.data.auth.TokenCipher
import dev.leonlatsch.photok.backup.cloud.data.credentials.FileCloudCredentialsRepository
import dev.leonlatsch.photok.backup.cloud.data.drive.GoogleDriveRepository
import dev.leonlatsch.photok.backup.cloud.domain.CloudAuth
import dev.leonlatsch.photok.backup.cloud.domain.CloudBackupRepository
import dev.leonlatsch.photok.backup.cloud.domain.CloudCredentialsRepository
import dev.leonlatsch.photok.backup.cloud.data.CloudOAuthConfig
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CloudModule {

    @Binds
    @Singleton
    abstract fun bindTokenCipher(impl: KeystoreTokenCipher): TokenCipher

    @Binds
    @Singleton
    abstract fun bindCloudTokenStore(impl: KeystoreCloudTokenStore): CloudTokenStore

    @Binds
    @Singleton
    abstract fun bindCloudAuth(impl: GoogleCloudAuth): CloudAuth

    @Binds
    @Singleton
    abstract fun bindCloudBackupRepository(impl: GoogleDriveRepository): CloudBackupRepository

    @Binds
    @Singleton
    abstract fun bindCloudCredentialsRepository(
        impl: FileCloudCredentialsRepository,
    ): CloudCredentialsRepository

    companion object {

        @Provides
        @Singleton
        fun provideCloudOAuthConfig() = CloudOAuthConfig()

        @Provides
        @Singleton
        fun provideCloudHttpClient() = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            // A large resumable upload or download has no total call limit.
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
