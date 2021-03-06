/*
 * Copyright (c) 2002-2020 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.io.pagecache.impl;

import com.sun.nio.file.ExtendedOpenOption;
import org.apache.commons.lang3.SystemUtils;

import java.io.File;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.util.HashSet;

import org.neo4j.internal.unsafe.UnsafeUtil;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.fs.StoreChannel;
import org.neo4j.io.fs.StoreFileChannel;
import org.neo4j.io.fs.StoreFileChannelUnwrapper;
import org.neo4j.io.pagecache.PageCursor;
import org.neo4j.io.pagecache.PageEvictionCallback;
import org.neo4j.io.pagecache.PageSwapper;
import org.neo4j.io.pagecache.impl.muninn.MuninnPageCache;

import static org.apache.commons.lang3.SystemUtils.IS_OS_LINUX;
import static org.neo4j.io.fs.DefaultFileSystemAbstraction.WRITE_OPTIONS;
import static org.neo4j.util.FeatureToggles.flag;
import static org.neo4j.util.FeatureToggles.getInteger;
import static org.neo4j.util.Preconditions.requirePowerOfTwo;

/**
 * A simple PageSwapper implementation that directs all page swapping to a
 * single file on the file system.
 *
 * It additionally tracks the file size precisely, to avoid calling into the
 * file system whenever the size of the given file is queried.
 */
public class SingleFilePageSwapper implements PageSwapper
{
    private static final int MAX_INTERRUPTED_CHANNEL_REOPEN_ATTEMPTS = 42;
    private static final boolean PRINT_REFLECTION_EXCEPTIONS = flag( SingleFilePageSwapper.class, "printReflectionExceptions", false );

    // Exponent of 2 of how many channels we open per file:
    private static final int GLOBAL_CHANNEL_STRIPE_POWER = getInteger( SingleFilePageSwapper.class, "channelStripePower", defaultChannelStripePower() );

    // Exponent of 2 of how many consecutive pages go to the same stripe
    private static final int CHANNEL_STRIPE_SHIFT = getInteger( SingleFilePageSwapper.class, "channelStripeShift", 4 );

    private static final int GLOBAL_CHANNEL_STRIPE_COUNT = 1 << GLOBAL_CHANNEL_STRIPE_POWER;
    private static final int GLOBAL_CHANNEL_STRIPE_MASK = stripeMask( GLOBAL_CHANNEL_STRIPE_COUNT );

    private static final int TOKEN_CHANNEL_STRIPE = 0;
    private static final long TOKEN_FILE_PAGE_ID = 0;

    private static final long FILE_SIZE_OFFSET = UnsafeUtil.getFieldOffset( SingleFilePageSwapper.class, "fileSize" );

    private static final ThreadLocal<ByteBuffer> PROXY_CACHE = new ThreadLocal<>();
    private static final Class<?> CLS_FILE_CHANNEL_IMPL = getInternalFileChannelClass();
    private static final MethodHandle POSITION_LOCK_GETTER = getPositionLockGetter();

    private static int defaultChannelStripePower()
    {
        if ( !SystemUtils.IS_OS_WINDOWS )
        {
            return 0;
        }
        int vcores = Runtime.getRuntime().availableProcessors();
        // Find the lowest 2's exponent that can accommodate 'vcores'
        int stripePower = 32 - Integer.numberOfLeadingZeros( vcores - 1 );
        return Math.min( 64, Math.max( 1, stripePower ) );
    }

    private static Class<?> getInternalFileChannelClass()
    {
        Class<?> cls = null;
        try
        {
            cls = Class.forName( "sun.nio.ch.FileChannelImpl" );
        }
        catch ( Throwable throwable )
        {
            if ( PRINT_REFLECTION_EXCEPTIONS )
            {
                throwable.printStackTrace();
            }
        }
        return cls;
    }

    private static int stripeMask( int count )
    {
        requirePowerOfTwo( count );
        return count - 1;
    }

    private static MethodHandle getPositionLockGetter()
    {
        try
        {
            if ( CLS_FILE_CHANNEL_IMPL != null )
            {
                MethodHandles.Lookup lookup = MethodHandles.lookup();
                Field field = CLS_FILE_CHANNEL_IMPL.getDeclaredField( "positionLock" );
                field.setAccessible( true );
                return lookup.unreflectGetter( field );
            }
            else
            {
                return null;
            }
        }
        catch ( Throwable e )
        {
            if ( PRINT_REFLECTION_EXCEPTIONS )
            {
                e.printStackTrace();
            }
            return null;
        }
    }

    private static ByteBuffer proxy( long buffer, int bufferLength ) throws IOException
    {
        ByteBuffer buf = PROXY_CACHE.get();
        if ( buf != null )
        {
            UnsafeUtil.initDirectByteBuffer( buf, buffer, bufferLength );
            return buf;
        }
        return createAndGetNewBuffer( buffer, bufferLength );
    }

    private static ByteBuffer createAndGetNewBuffer( long buffer, int bufferLength ) throws IOException
    {
        ByteBuffer buf;
        try
        {
            buf = UnsafeUtil.newDirectByteBuffer( buffer, bufferLength );
        }
        catch ( Exception e )
        {
            throw new IOException( e );
        }
        PROXY_CACHE.set( buf );
        return buf;
    }

    private final FileSystemAbstraction fs;
    private final File file;
    private final int filePageSize;
    private volatile PageEvictionCallback onEviction;
    private final StoreChannel[] channels;
    private FileLock fileLock;
    private final boolean hasPositionLock;
    private final int channelStripeCount;
    private final int channelStripeMask;

    // Guarded by synchronized(this). See tryReopen() and close().
    private boolean closed;

    @SuppressWarnings( "unused" ) // Accessed through unsafe
    private volatile long fileSize;

    SingleFilePageSwapper( File file, FileSystemAbstraction fs, int filePageSize, PageEvictionCallback onEviction, boolean noChannelStriping,
            boolean useDirectIO ) throws IOException
    {
        this.fs = fs;
        this.file = file;
        if ( noChannelStriping )
        {
            this.channelStripeCount = 1;
            this.channelStripeMask = stripeMask( channelStripeCount );
        }
        else
        {
            this.channelStripeCount = GLOBAL_CHANNEL_STRIPE_COUNT;
            this.channelStripeMask = GLOBAL_CHANNEL_STRIPE_MASK;
        }
        if ( useDirectIO )
        {
            validateDirectIOPossibility( file, filePageSize );
        }
        this.channels = new StoreChannel[channelStripeCount];
        for ( int i = 0; i < channelStripeCount; i++ )
        {
            var openOptions = new HashSet<>( WRITE_OPTIONS );
            openOptions.add( ExtendedOpenOption.DIRECT );
            channels[i] = useDirectIO ? fs.open( file, openOptions ) : fs.write( file );
        }
        this.filePageSize = filePageSize;
        this.onEviction = onEviction;
        increaseFileSizeTo( channels[TOKEN_CHANNEL_STRIPE].size() );

        try
        {
            acquireLock();
        }
        catch ( IOException e )
        {
            closeAndCollectExceptions( 0, e );
        }
        hasPositionLock = channels[0].getClass() == StoreFileChannel.class
                && StoreFileChannelUnwrapper.unwrap( channels[0] ).getClass() == CLS_FILE_CHANNEL_IMPL;
    }

    private void validateDirectIOPossibility( File file, int filePageSize ) throws IOException
    {
        if ( !IS_OS_LINUX )
        {
            throw new IllegalArgumentException( "DirectIO support is available only on Linux." );
        }
        final long blockSize = fs.getBlockSize( file );
        long value = filePageSize / blockSize;
        if ( value * blockSize != filePageSize )
        {
            throw new IllegalArgumentException( "Direct IO can be used only when page cache page size is a multiplier of a block size. "
                    + "File page size: " + filePageSize + ", block size: " + blockSize );
        }
    }

    private void increaseFileSizeTo( long newFileSize )
    {
        long currentFileSize;
        do
        {
            currentFileSize = getCurrentFileSize();
        }
        while ( currentFileSize < newFileSize && !UnsafeUtil.compareAndSwapLong(
                this, FILE_SIZE_OFFSET, currentFileSize, newFileSize ) );
    }

    private long getCurrentFileSize()
    {
        return UnsafeUtil.getLongVolatile( this, FILE_SIZE_OFFSET );
    }

    private void setCurrentFileSize( long size )
    {
        UnsafeUtil.putLongVolatile( this, FILE_SIZE_OFFSET, size );
    }

    private void acquireLock() throws IOException
    {
        if ( SystemUtils.IS_OS_WINDOWS )
        {
            // We don't take file locks on the individual store files on Windows, because once you've taking
            // a file lock on a channel, you can only do IO on that file through that channel. This would
            // mean that we can't stripe our FileChannels on Windows, which is the platform that needs striped
            // channels the most because of lack of pwrite and pread support.
            // This is generally fine, because the StoreLocker and the lock file will protect the store from
            // being opened by multiple instances at the same time anyway.
            return;
        }

        try
        {
            fileLock = channels[TOKEN_CHANNEL_STRIPE].tryLock();
            if ( fileLock == null )
            {
                throw new FileLockException( file );
            }
        }
        catch ( OverlappingFileLockException e )
        {
            throw new FileLockException( file, e );
        }
    }

    private StoreChannel channel( long filePageId )
    {
        int stripe = stripe( filePageId );
        return channels[stripe];
    }

    private int stripe( long filePageId )
    {
        return (int) (filePageId >>> CHANNEL_STRIPE_SHIFT) & channelStripeMask;
    }

    private int swapIn( StoreChannel channel, long bufferAddress, long fileOffset, int filePageSize ) throws IOException
    {
        int readTotal = 0;
        try
        {
            ByteBuffer bufferProxy = proxy( bufferAddress, filePageSize );
            int read;
            do
            {
                read = channel.read( bufferProxy, fileOffset + readTotal );
            }
            while ( read != -1 && (readTotal += read) < filePageSize );

            // Zero-fill the rest.
            int rest = filePageSize - readTotal;
            if ( rest > 0 )
            {
                UnsafeUtil.setMemory( bufferAddress + readTotal, rest, MuninnPageCache.ZERO_BYTE );
            }
            return readTotal;
        }
        catch ( IOException e )
        {
            throw e;
        }
        catch ( Throwable e )
        {
            throw new IOException( formatSwapInErrorMessage( fileOffset, filePageSize, readTotal ), e );
        }
    }

    private String formatSwapInErrorMessage( long fileOffset, int filePageSize, int readTotal )
    {
        return "Read failed after " + readTotal + " of " + filePageSize + " bytes from fileOffset " + fileOffset + ".";
    }

    private int swapOut( long bufferAddress, long fileOffset, StoreChannel channel ) throws IOException
    {
        try
        {
            ByteBuffer bufferProxy = proxy( bufferAddress, filePageSize );
            channel.writeAll( bufferProxy, fileOffset );
        }
        catch ( IOException e )
        {
            throw e;
        }
        catch ( Throwable e )
        {
            throw new IOException( e );
        }
        return filePageSize;
    }

    private void clear( long bufferAddress, int bufferSize )
    {
        UnsafeUtil.setMemory( bufferAddress, bufferSize, MuninnPageCache.ZERO_BYTE );
    }

    @Override
    public long read( long filePageId, long bufferAddress ) throws IOException
    {
        return readAndRetryIfInterrupted( filePageId, bufferAddress, MAX_INTERRUPTED_CHANNEL_REOPEN_ATTEMPTS );
    }

    private long readAndRetryIfInterrupted( long filePageId, long bufferAddress, int attemptsLeft ) throws IOException
    {
        long fileOffset = pageIdToPosition( filePageId );
        try
        {
            if ( fileOffset < getCurrentFileSize() )
            {
                return swapIn( channel( filePageId ), bufferAddress, fileOffset, filePageSize );
            }
            else
            {
                clear( bufferAddress, filePageSize );
            }
        }
        catch ( ClosedChannelException e )
        {
            tryReopen( filePageId, e );

            if ( attemptsLeft < 1 )
            {
                throw new IOException( "IO failed due to interruption", e );
            }

            boolean interrupted = Thread.interrupted();
            long bytesRead = readAndRetryIfInterrupted( filePageId, bufferAddress, attemptsLeft - 1 );
            if ( interrupted )
            {
                Thread.currentThread().interrupt();
            }
            return bytesRead;
        }
        return 0;
    }

    @Override
    public long read( long startFilePageId, long[] bufferAddresses, int arrayOffset, int length ) throws IOException
    {
        if ( POSITION_LOCK_GETTER != null && hasPositionLock )
        {
            try
            {
                return readPositionedVectoredToFileChannel( startFilePageId, bufferAddresses, arrayOffset, length );
            }
            catch ( IOException ioe )
            {
                throw ioe;
            }
            catch ( Exception ignore )
            {
                // There's a lot of reflection going on in that method. We ignore everything that can go wrong, and
                // isn't exactly an IOException. Instead, we'll try our fallback code and see what it says.
            }
        }
        return readPositionedVectoredFallback( startFilePageId, bufferAddresses, arrayOffset, length );
    }

    private long readPositionedVectoredToFileChannel(
            long startFilePageId, long[] bufferAddresses, int arrayOffset, int length ) throws Exception
    {
        long fileOffset = pageIdToPosition( startFilePageId );
        FileChannel channel = unwrappedChannel( startFilePageId );
        ByteBuffer[] srcs = convertToByteBuffers( bufferAddresses, arrayOffset, length );
        long bytesRead = lockPositionReadVectorAndRetryIfInterrupted( startFilePageId, channel, fileOffset, srcs, MAX_INTERRUPTED_CHANNEL_REOPEN_ATTEMPTS );
        if ( bytesRead == -1 )
        {
            for ( long address : bufferAddresses )
            {
                UnsafeUtil.setMemory( address, filePageSize, MuninnPageCache.ZERO_BYTE );
            }
            return 0;
        }
        else if ( bytesRead < ((long) filePageSize) * length )
        {
            int pagesRead = (int) (bytesRead / filePageSize);
            int bytesReadIntoLastReadPage = (int) (bytesRead % filePageSize);
            int pagesNeedingZeroing = length - pagesRead;
            for ( int i = 0; i < pagesNeedingZeroing; i++ )
            {
                long address = bufferAddresses[arrayOffset + pagesRead + i];
                long bytesToZero = filePageSize;
                if ( i == 0 )
                {
                    address += bytesReadIntoLastReadPage;
                    bytesToZero -= bytesReadIntoLastReadPage;
                }
                UnsafeUtil.setMemory( address, bytesToZero, MuninnPageCache.ZERO_BYTE );
            }
        }
        return bytesRead;
    }

    private long lockPositionReadVectorAndRetryIfInterrupted( long filePageId, FileChannel channel, long fileOffset, ByteBuffer[] srcs, int attemptsLeft )
            throws IOException
    {
        try
        {
            long toRead = filePageSize * (long) srcs.length;
            long read;
            long readTotal = 0;
            synchronized ( positionLock( channel ) )
            {
                channel.position( fileOffset );
                do
                {
                    read = channel.read( srcs );
                }
                while ( read != -1 && (readTotal += read) < toRead );
                return readTotal;
            }
        }
        catch ( ClosedChannelException e )
        {
            tryReopen( filePageId, e );

            if ( attemptsLeft < 1 )
            {
                throw new IOException( "IO failed due to interruption", e );
            }

            boolean interrupted = Thread.interrupted();
            channel = unwrappedChannel( filePageId );
            long bytesWritten = lockPositionReadVectorAndRetryIfInterrupted( filePageId, channel, fileOffset, srcs, attemptsLeft - 1 );
            if ( interrupted )
            {
                Thread.currentThread().interrupt();
            }
            return bytesWritten;
        }
    }

    private int readPositionedVectoredFallback( long startFilePageId, long[] bufferAddresses, int arrayOffset, int length ) throws IOException
    {
        int bytes = 0;
        for ( int i = 0; i < length; i++ )
        {
            long address = bufferAddresses[arrayOffset + i];
            bytes += read( startFilePageId + i, address );
        }
        return bytes;
    }

    @Override
    public long write( long filePageId, long bufferAddress ) throws IOException
    {
        return writeAndRetryIfInterrupted( filePageId, bufferAddress, MAX_INTERRUPTED_CHANNEL_REOPEN_ATTEMPTS );
    }

    private long writeAndRetryIfInterrupted( long filePageId, long bufferAddress, int attemptsLeft ) throws IOException
    {
        long fileOffset = pageIdToPosition( filePageId );
        increaseFileSizeTo( fileOffset + filePageSize );
        try
        {
            StoreChannel channel = channel( filePageId );
            return swapOut( bufferAddress, fileOffset, channel );
        }
        catch ( ClosedChannelException e )
        {
            tryReopen( filePageId, e );

            if ( attemptsLeft < 1 )
            {
                throw new IOException( "IO failed due to interruption", e );
            }

            boolean interrupted = Thread.interrupted();
            long bytesWritten = writeAndRetryIfInterrupted( filePageId, bufferAddress, attemptsLeft - 1 );
            if ( interrupted )
            {
                Thread.currentThread().interrupt();
            }
            return bytesWritten;
        }
    }

    @Override
    public long write( long startFilePageId, long[] bufferAddresses, int arrayOffset, int length ) throws IOException
    {
        if ( POSITION_LOCK_GETTER != null && hasPositionLock )
        {
            try
            {
                return writePositionedVectoredToFileChannel( startFilePageId, bufferAddresses, arrayOffset, length );
            }
            catch ( IOException ioe )
            {
                throw ioe;
            }
            catch ( Exception exception )
            {
                // There's a lot of reflection going on in that method. We ignore everything that can go wrong, and
                // isn't exactly an IOException. Instead, we'll try our fallback code and see what it says.
                if ( PRINT_REFLECTION_EXCEPTIONS )
                {
                    exception.printStackTrace();
                }
            }
        }
        return writePositionVectoredFallback( startFilePageId, bufferAddresses, arrayOffset, length );
    }

    private long writePositionedVectoredToFileChannel(
            long startFilePageId, long[] bufferAddresses, int arrayOffset, int length ) throws Exception
    {
        long fileOffset = pageIdToPosition( startFilePageId );
        increaseFileSizeTo( fileOffset + (((long) filePageSize) * length) );
        FileChannel channel = unwrappedChannel( startFilePageId );
        ByteBuffer[] srcs = convertToByteBuffers( bufferAddresses, arrayOffset, length );
        return lockPositionWriteVectorAndRetryIfInterrupted( startFilePageId, channel, fileOffset, srcs, MAX_INTERRUPTED_CHANNEL_REOPEN_ATTEMPTS );
    }

    private ByteBuffer[] convertToByteBuffers( long[] bufferAddresses, int arrayOffset, int length ) throws Exception
    {
        ByteBuffer[] buffers = new ByteBuffer[length];
        for ( int i = 0; i < length; i++ )
        {
            long address = bufferAddresses[arrayOffset + i];
            buffers[i] = UnsafeUtil.newDirectByteBuffer( address, filePageSize );
        }
        return buffers;
    }

    private FileChannel unwrappedChannel( long startFilePageId )
    {
        StoreChannel storeChannel = channel( startFilePageId );
        return StoreFileChannelUnwrapper.unwrap( storeChannel );
    }

    private long lockPositionWriteVectorAndRetryIfInterrupted( long filePageId, FileChannel channel, long fileOffset, ByteBuffer[] srcs, int attemptsLeft )
            throws IOException
    {
        try
        {
            long toWrite = filePageSize * (long) srcs.length;
            long bytesWritten = 0;
            synchronized ( positionLock( channel ) )
            {
                channel.position( fileOffset );
                do
                {
                    bytesWritten += channel.write( srcs );
                }
                while ( bytesWritten < toWrite );
                return bytesWritten;
            }
        }
        catch ( ClosedChannelException e )
        {
            tryReopen( filePageId, e );

            if ( attemptsLeft < 1 )
            {
                throw new IOException( "IO failed due to interruption", e );
            }

            boolean interrupted = Thread.interrupted();
            channel = unwrappedChannel( filePageId );
            long bytesWritten = lockPositionWriteVectorAndRetryIfInterrupted( filePageId, channel, fileOffset, srcs, attemptsLeft - 1 );
            if ( interrupted )
            {
                Thread.currentThread().interrupt();
            }
            return bytesWritten;
        }
    }

    private Object positionLock( FileChannel channel )
    {
        try
        {
            return (Object) POSITION_LOCK_GETTER.invoke( channel );
        }
        catch ( Throwable th )
        {
            throw new LinkageError( "No getter for FileChannel.positionLock", th );
        }
    }

    private int writePositionVectoredFallback( long startFilePageId, long[] bufferAddresses, int arrayOffset, int length )
            throws IOException
    {
        int bytes = 0;
        for ( int i = 0; i < length; i++ )
        {
            long address = bufferAddresses[arrayOffset + i];
            bytes += write( startFilePageId + i, address );
        }
        return bytes;
    }

    @Override
    public void evicted( long filePageId )
    {
        PageEvictionCallback callback = this.onEviction;
        if ( callback != null )
        {
            callback.onEvict( filePageId );
        }
    }

    @Override
    public File file()
    {
        return file;
    }

    private long pageIdToPosition( long pageId )
    {
        return filePageSize * pageId;
    }

    @Override
    public boolean equals( Object o )
    {
        if ( this == o )
        {
            return true;
        }
        if ( o == null || getClass() != o.getClass() )
        {
            return false;
        }

        SingleFilePageSwapper that = (SingleFilePageSwapper) o;

        return file.equals( that.file );

    }

    @Override
    public int hashCode()
    {
        return file.hashCode();
    }

    /**
     * Reopens the channel if it has been closed and the close() method on
     * this swapper has not been called. In other words, if the channel has
     * been "accidentally" closed by an interrupt or the like.
     *
     * If the channel has been explicitly closed with the PageSwapper#close()
     * method, then this method will re-throw the passed-in exception.
     *
     * If the reopening of the file fails with an exception for some reason,
     * then that exception is added as a suppressed exception to the passed in
     * ClosedChannelException, and the CCE is then rethrown.
     */
    private synchronized void tryReopen( long filePageId, ClosedChannelException closedException ) throws ClosedChannelException
    {
        int stripe = stripe( filePageId );
        StoreChannel channel = channels[stripe];
        if ( channel.isOpen() )
        {
            // Someone got ahead of us, presumably. Nothing to do.
            return;
        }

        if ( closed )
        {
            // We've been explicitly closed, so we shouldn't reopen the
            // channel.
            throw closedException;
        }

        try
        {
            channels[stripe] = fs.write( file );
            if ( stripe == TOKEN_CHANNEL_STRIPE )
            {
                // The closing of a FileChannel also releases all associated file locks.
                acquireLock();
            }
        }
        catch ( IOException e )
        {
            closedException.addSuppressed( e );
            throw closedException;
        }
    }

    @Override
    public synchronized void close() throws IOException
    {
        closed = true;
        try
        {
            closeAndCollectExceptions( 0, null );
        }
        finally
        {
            // Eagerly relinquish our reference to the onEviction callback, because even though
            // we've closed the PagedFile at this point, there are likely still pages in the cache that are bound to
            // this swapper, and will stay bound, until the eviction threads eventually gets around to kicking them out.
            // It is especially important to null out the onEviction callback field, because it is in turn holding on to
            // the striped translation table, which can be a rather large structure.
            onEviction = null;
        }
    }

    private void closeAndCollectExceptions( int channelIndex, IOException exception ) throws IOException
    {
        if ( channelIndex == channels.length )
        {
            if ( exception != null )
            {
                throw exception;
            }
            return;
        }

        try
        {
            channels[channelIndex].close();
        }
        catch ( IOException e )
        {
            if ( exception == null )
            {
                exception = e;
            }
            else
            {
                exception.addSuppressed( e );
            }
        }
        closeAndCollectExceptions( channelIndex + 1, exception );
    }

    @Override
    public synchronized void closeAndDelete() throws IOException
    {
        close();
        fs.deleteFile( file );
    }

    @Override
    public void force() throws IOException
    {
        forceAndRetryIfInterrupted( MAX_INTERRUPTED_CHANNEL_REOPEN_ATTEMPTS );
    }

    private void forceAndRetryIfInterrupted( int attemptsLeft ) throws IOException
    {
        try
        {
            channel( TOKEN_FILE_PAGE_ID ).force( false );
        }
        catch ( ClosedChannelException e )
        {
            tryReopen( TOKEN_FILE_PAGE_ID, e );

            if ( attemptsLeft < 1 )
            {
                throw new IOException( "IO failed due to interruption", e );
            }

            boolean interrupted = Thread.interrupted();
            forceAndRetryIfInterrupted( attemptsLeft - 1 );
            if ( interrupted )
            {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public long getLastPageId()
    {
        long channelSize = getCurrentFileSize();
        if ( channelSize == 0 )
        {
            return PageCursor.UNBOUND_PAGE_ID;
        }
        long div = channelSize / filePageSize;
        long mod = channelSize % filePageSize;
        return mod == 0 ? div - 1 : div;
    }

    @Override
    public void truncate() throws IOException
    {
        truncateAndRetryIfInterrupted( MAX_INTERRUPTED_CHANNEL_REOPEN_ATTEMPTS );
    }

    private void truncateAndRetryIfInterrupted( int attemptsLeft ) throws IOException
    {
        setCurrentFileSize( 0 );
        try
        {
            channel( TOKEN_FILE_PAGE_ID ).truncate( 0 );
        }
        catch ( ClosedChannelException e )
        {
            tryReopen( TOKEN_FILE_PAGE_ID, e );

            if ( attemptsLeft < 1 )
            {
                throw new IOException( "IO failed due to interruption", e );
            }

            boolean interrupted = Thread.interrupted();
            truncateAndRetryIfInterrupted( attemptsLeft - 1 );
            if ( interrupted )
            {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public String toString()
    {
        return "SingleFilePageSwapper{" +
                "filePageSize=" + filePageSize +
                ", file=" + file +
                '}';
    }
}
