package link.oppolink.bluetooth

import android.bluetooth.BluetoothManager
import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Singleton Bluetooth bindings. The adapter is process-wide on Android, so
 * scoping the wrappers as singletons matches the platform's lifecycle and
 * avoids the well-known "stale BluetoothLeScanner after Bluetooth toggle"
 * surface that you get when a transient component captures the adapter.
 */
@Module
@InstallIn(SingletonComponent::class)
object BluetoothModule {

    @Provides
    @Singleton
    fun provideBluetoothManager(@ApplicationContext context: Context): BluetoothManager =
        context.getSystemService(BluetoothManager::class.java)

    @Provides
    @Singleton
    fun providePeerAdvertiser(
        @ApplicationContext context: Context,
        manager: BluetoothManager,
    ): PeerAdvertiser =
        RealPeerAdvertiser(context, manager.adapter?.bluetoothLeAdvertiser)

    @Provides
    @Singleton
    fun providePeerScanner(
        @ApplicationContext context: Context,
        manager: BluetoothManager,
    ): PeerScanner =
        RealPeerScanner(context, manager.adapter?.bluetoothLeScanner)

    @Provides
    @Singleton
    fun providePeerConnector(
        @ApplicationContext context: Context,
        manager: BluetoothManager,
    ): PeerConnector =
        RealPeerConnector(context, manager.adapter, manager)
}
