/*
 *  Copyright (c) 2012-2013 Malhar, Inc.
 *  All Rights Reserved.
 */
package com.datatorrent.flume.storage;

import java.io.DataInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;

import javax.validation.constraints.NotNull;

import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.flume.Context;
import org.apache.flume.conf.Configurable;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

import com.datatorrent.api.Component;
import com.datatorrent.common.util.Slice;
import com.datatorrent.flume.sink.Server;

/**
 * HDFSStorage is developed to store and retrieve the data from HDFS
 * <p />
 * The properties that can be set on HDFSStorage are: <br />
 * baseDir - The base directory where the data is going to be stored <br />
 * restore - This is used to restore the application from previous failure <br />
 * blockSize - The maximum size of the each file to created. <br />
 *
 * @author Gaurav Gupta <gaurav@datatorrent.com>
 * @since 0.9.3
 */
public class HDFSStorage implements Storage, Configurable, Component<com.datatorrent.api.Context>
{
  public static final int DEFAULT_BLOCK_SIZE = 64 * 1024 * 1024;
  public static final String BASE_DIR_KEY = "baseDir";
  public static final String RESTORE_KEY = "restore";
  public static final String BLOCKSIZE = "blockSize";
  private static final String IDENTITY_FILE = "counter";
  // private static final String CLEAN_FILE = "clean-counter";
  private static final String OFFSET_SUFFIX = "-offsetFile";
  private static final String BOOK_KEEPING_FILE_OFFSET = "-bookKeepingOffsetFile";
  private static final String FLUSHED_IDENTITY_FILE = "flushedCounter";
  private static final String CLEAN_OFFSET_FILE = "cleanoffsetFile";
  public static final String OFFSET_KEY = "offset";
  private static final int IDENTIFIER_SIZE = 8;
  private static final int DATA_LENGTH_BYTE_SIZE = 4;
  /**
   * Identifier for this storage.
   */
  @NotNull
  private String id;
  /**
   * The baseDir where the storage facility is going to create files.
   */
  @NotNull
  private String baseDir;
  /**
   * The block size to be used to create the storage files
   */
  private long blockSize;
  /**
   *
   */
  private boolean restore;
  /**
   * This identifies the current file number
   */
  private long currentWrittenFile;
  /**
   * This identifies the file number that has been flushed
   */
  private long flushedFileCounter;
  /**
   * The file that stores the fileCounter information
   */
  // private Path fileCounterFile;
  /**
   * The file that stores the flushed fileCounter information
   */
  private Path flushedCounterFile;
  /**
   * This identifies the last cleaned file number
   */
  private long cleanedFileCounter;
  /**
   * The file that stores the clean file counter information
   */
  // private Path cleanFileCounterFile;
  /**
   * The file that stores the clean file offset information
   */
  private Path cleanFileOffsetFile;
  private FileSystem fs;
  private FSDataOutputStream dataStream;
  ArrayList<DataBlock> files2Commit = new ArrayList<DataBlock>();
  /**
   * The offset in the current opened file
   */
  private long fileWriteOffset;
  private FSDataInputStream readStream;
  private long retrievalOffset;
  private long retrievalFile;
  private int offset;
  private long flushedLong;
  private long flushedFileWriteOffset;
  private long bookKeepingFileOffset;
  private byte[] cleanedOffset = new byte[8];
  private long skipOffset;
  private long skipFile;
  private transient Path basePath;

  public HDFSStorage()
  {
    this.restore = true;
  }

  /**
   * This stores the Identifier information identified in the last store function call
   *
   * @param ctx
   */
  @Override
  public void configure(Context ctx)
  {
    String tempId = ctx.getString(ID);
    if (tempId == null) {
      if (id == null) {
        throw new IllegalArgumentException("id can't be  null.");
      }
    } else {
      id = tempId;
    }

    String tempBaseDir = ctx.getString(BASE_DIR_KEY);
    if (tempBaseDir != null) {
      baseDir = tempBaseDir;
    }

    restore = ctx.getBoolean(RESTORE_KEY, restore);
    Long tempBlockSize = ctx.getLong(BLOCKSIZE);
    if (tempBlockSize != null) {
      blockSize = tempBlockSize;
    }

  }

  /**
   * This function reads the file at a location and return the bytes stored in the file "
   *
   * @param path
   *          - the location of the file
   * @return
   * @throws IOException
   */
  byte[] readData(Path path) throws IOException
  {
    DataInputStream is = new DataInputStream(fs.open(path));
    byte[] bytes = new byte[is.available()];
    is.readFully(bytes);
    is.close();
    return bytes;
  }

  /**
   * This function writes the bytes to a file specified by the path
   *
   * @param path
   *          the file location
   * @param data
   *          the data to be written to the file
   * @return
   * @throws IOException
   */
  private FSDataOutputStream writeData(Path path, byte[] data) throws IOException
  {
    FSDataOutputStream fsOutputStream;
    if (fs.getScheme().equals("file")) {
      // local FS does not support hflush and does not flush native stream
      fsOutputStream = new FSDataOutputStream(new FileOutputStream(Path.getPathWithoutSchemeAndAuthority(path).toString()), null);
    } else {
      fsOutputStream = fs.create(path);
    }
    fsOutputStream.write(data);
    return fsOutputStream;
  }

  private long calculateOffset(long fileOffset, long fileCounter)
  {
    return ((fileCounter << 32) | (fileOffset & 0xffffffffl));
  }

  @Override
  public byte[] store(Slice slice)
  {
    // logger.debug("store message ");
    int bytesToWrite = slice.length + DATA_LENGTH_BYTE_SIZE;
    if (currentWrittenFile < skipFile) {
      fileWriteOffset += bytesToWrite;
      if (fileWriteOffset >= bookKeepingFileOffset) {
        files2Commit.add(new DataBlock(null, bookKeepingFileOffset, new Path(basePath, currentWrittenFile + OFFSET_SUFFIX), currentWrittenFile));
        currentWrittenFile++;
        if (fileWriteOffset > bookKeepingFileOffset) {
          fileWriteOffset = bytesToWrite;
        } else {
          fileWriteOffset = 0;
        }
        try {
          bookKeepingFileOffset = getFlushedFileWriteOffset(new Path(basePath, currentWrittenFile + BOOK_KEEPING_FILE_OFFSET));
        } catch (IOException e) {
          bookKeepingFileOffset = 0;
        }
      }
      return null;
    }

    if (flushedFileCounter == currentWrittenFile && dataStream == null) {
      currentWrittenFile++;
      fileWriteOffset = 0;
    }

    if (flushedFileCounter == skipFile && skipFile != -1) {
      skipFile++;
    }

    if (fileWriteOffset + bytesToWrite < blockSize) {
      try {
        /* write length and the actual data to the file */
        if (fileWriteOffset == 0) {
          // writeData(flushedCounterFile, String.valueOf(currentWrittenFile).getBytes()).close();
          dataStream = writeData(new Path(basePath, String.valueOf(currentWrittenFile)), Ints.toByteArray(slice.length));
          dataStream.write(slice.buffer, slice.offset, slice.length);
        } else {
          dataStream.write(Ints.toByteArray(slice.length));
          dataStream.write(slice.buffer, slice.offset, slice.length);
        }
        fileWriteOffset += bytesToWrite;

        byte[] fileOffset = null;
        if ((currentWrittenFile > skipFile) || (currentWrittenFile == skipFile && fileWriteOffset > skipOffset)) {
          skipFile = -1;
          fileOffset = new byte[IDENTIFIER_SIZE];
          Server.writeLong(fileOffset, 0, calculateOffset(fileWriteOffset, currentWrittenFile));
        }
        return fileOffset;
      } catch (IOException ex) {
        logger.warn("Error while storing the bytes {}", ex.getMessage());
        closeFs();
        throw new RuntimeException(ex);
      }
    }
    DataBlock db = new DataBlock(dataStream, fileWriteOffset, new Path(basePath, currentWrittenFile + OFFSET_SUFFIX), currentWrittenFile);
    db.close();
    files2Commit.add(db);
    fileWriteOffset = 0;
    ++currentWrittenFile;
    return store(slice);
  }

  /**
   *
   * @param b
   * @param size
   * @param startIndex
   * @return
   */
  long byteArrayToLong(byte[] b, int startIndex)
  {
    final byte b1 = 0;
    return Longs.fromBytes(b1, b1, b1, b1, b[3 + startIndex], b[2 + startIndex], b[1 + startIndex], b[startIndex]);
  }

  @Override
  public byte[] retrieve(byte[] identifier)
  {
    skipFile = -1;
    skipOffset = 0;
    logger.debug("retrieve with address {}", Arrays.toString(identifier));
    // flushing the last incomplete flushed file
    closeUnflushedFiles();

    retrievalOffset = byteArrayToLong(identifier, 0);
    retrievalFile = byteArrayToLong(identifier, offset);

    if (retrievalFile == 0 && retrievalOffset == 0 && currentWrittenFile == 0 && fileWriteOffset == 0) {
      skipOffset = 0;
      return null;
    }

    // making sure that the deleted address is not requested again
    if (retrievalFile != 0 || retrievalOffset != 0) {
      long cleanedFile = byteArrayToLong(cleanedOffset, offset);
      if (retrievalFile < cleanedFile || (retrievalFile == cleanedFile && retrievalOffset < byteArrayToLong(cleanedOffset, 0))) {
        logger.warn("The address asked has been deleted");
        closeFs();
        throw new IllegalArgumentException(String.format("The data for address %s has already been deleted", Arrays.toString(identifier)));
      }
    }

    // we have just started
    if (retrievalFile == 0 && retrievalOffset == 0) {
      retrievalFile = byteArrayToLong(cleanedOffset, offset);
      retrievalOffset = byteArrayToLong(cleanedOffset, 0);
    }

    if ((retrievalFile > flushedFileCounter)) {
      skipFile = retrievalFile;
      skipOffset = retrievalOffset;
      retrievalFile = -1;
      return null;
    }
    if ((retrievalFile == flushedFileCounter && retrievalOffset >= flushedFileWriteOffset)) {
      skipFile = retrievalFile;
      skipOffset = retrievalOffset - flushedFileWriteOffset;
      retrievalFile = -1;
      return null;
    }

    try {
      if (readStream != null) {
        readStream.close();
      }
      Path path = new Path(basePath, String.valueOf(retrievalFile));
      if (!fs.exists(path)) {
        retrievalFile = -1;
        closeFs();
        throw new RuntimeException(String.format("File %s does not exist", path.toString()));
      }

      byte[] flushedOffset = readData(new Path(basePath, retrievalFile + OFFSET_SUFFIX));
      flushedLong = Server.readLong(flushedOffset, 0);
      while (retrievalOffset >= flushedLong && retrievalFile < flushedFileCounter) {
        retrievalOffset -= flushedLong;
        retrievalFile++;
        flushedOffset = readData(new Path(basePath, retrievalFile + OFFSET_SUFFIX));
        path = new Path(basePath, String.valueOf(retrievalFile));
        flushedLong = Server.readLong(flushedOffset, 0);
      }

      if (retrievalOffset >= flushedLong) {
        logger.warn("data not flushed for the given identifier");
        retrievalFile = -1;
        return null;
      }
      readStream = new FSDataInputStream(fs.open(path));
      readStream.seek(retrievalOffset);
      return retrieveHelper();
    } catch (IOException e) {
      logger.warn(e.getMessage());
      resetRetrieval();
      return null;
    }
  }

  private void resetRetrieval()
  {
    try {
      if (readStream != null) {
        readStream.close();
      }
    } catch (IOException io) {
      logger.warn("Failed Close", io);
    } finally {
      retrievalFile = -1;
      readStream = null;
    }
  }

  private byte[] retrieveHelper() throws IOException
  {
    int length = readStream.readInt();
    byte[] data = new byte[length + IDENTIFIER_SIZE];
    readStream.readFully(retrievalOffset + 4, data, IDENTIFIER_SIZE, length);
    retrievalOffset += length + DATA_LENGTH_BYTE_SIZE;
    if (retrievalOffset >= flushedLong) {
      Server.writeLong(data, 0, calculateOffset(0, retrievalFile + 1));
    } else {
      Server.writeLong(data, 0, calculateOffset(retrievalOffset, retrievalFile));
    }
    return data;
  }

  @Override
  public byte[] retrieveNext()
  {
    // logger.debug("retrieveNext");
    if (retrievalFile == -1) {
      closeFs();
      throw new RuntimeException("Call retrieve first");
    }

    if (retrievalFile > flushedFileCounter) {
      logger.warn("data is not flushed");
      return null;
    }

    try {
      if (readStream == null) {
        readStream = new FSDataInputStream(fs.open(new Path(basePath, String.valueOf(retrievalFile))));
        byte[] flushedOffset = readData(new Path(basePath, retrievalFile + OFFSET_SUFFIX));
        flushedLong = Server.readLong(flushedOffset, 0);
      }

      if (retrievalOffset >= flushedLong) {
        retrievalFile++;
        retrievalOffset = 0;

        if (retrievalFile > flushedFileCounter) {
          logger.warn("data is not flushed");
          return null;
        }

        readStream.close();
        readStream = new FSDataInputStream(fs.open(new Path(basePath, String.valueOf(retrievalFile))));
        byte[] flushedOffset = readData(new Path(basePath, retrievalFile + OFFSET_SUFFIX));
        flushedLong = Server.readLong(flushedOffset, 0);
      }
      readStream.seek(retrievalOffset);
      return retrieveHelper();
    } catch (IOException e) {
      logger.warn(" error while retrieving {}", e.getMessage());
      return null;
    }
  }

  @Override
  @SuppressWarnings("AssignmentToCollectionOrArrayFieldFromParameter")
  public void clean(byte[] identifier)
  {
    logger.info("clean {}", Arrays.toString(identifier));
    long cleanFileIndex = byteArrayToLong(identifier, offset);

    long cleanFileOffset = byteArrayToLong(identifier, 0);
    if (flushedFileCounter == -1) {
      identifier = new byte[8];
    }
    // This is to make sure that we clean only the data that is flushed
    else if (cleanFileIndex > flushedFileCounter || (cleanFileIndex == flushedFileCounter && cleanFileOffset >= flushedFileWriteOffset)) {
      cleanFileIndex = flushedFileCounter;
      cleanFileOffset = flushedFileWriteOffset;
      Server.writeLong(identifier, 0, calculateOffset(cleanFileOffset, cleanFileIndex));
    }
    cleanedOffset = identifier;

    try {
      writeData(cleanFileOffsetFile, identifier).close();
      if (cleanedFileCounter >= cleanFileIndex) {
        return;
      }
      do {
        Path path = new Path(basePath, String.valueOf(cleanedFileCounter));
        if (fs.exists(path) && fs.isFile(path)) {
          fs.delete(path, false);
        }
        path = new Path(basePath, cleanedFileCounter + OFFSET_SUFFIX);
        if (fs.exists(path) && fs.isFile(path)) {
          fs.delete(path, false);
        }
        path = new Path(basePath, cleanedFileCounter + BOOK_KEEPING_FILE_OFFSET);
        if (fs.exists(path) && fs.isFile(path)) {
          fs.delete(path, false);
        }
        ++cleanedFileCounter;
      } while (cleanedFileCounter < cleanFileIndex);
      // writeData(cleanFileCounterFile, String.valueOf(cleanedFileCounter).getBytes()).close();

    } catch (IOException e) {
      logger.warn("not able to close the streams {}", e.getMessage());
      closeFs();
      throw new RuntimeException(e);
    } finally {

    }
  }

  /**
   * This is used mainly for cleaning up of counter files created
   */
  void cleanHelperFiles()
  {
    try {
      fs.delete(basePath, true);
    } catch (IOException e) {
      logger.warn(e.getMessage());
    }
  }

  private void closeUnflushedFiles()
  {
    try {

      // closing the stream
      if (dataStream != null) {
        dataStream.close();
        dataStream = null;
        // currentWrittenFile++;
        // fileWriteOffset = 0;
      }

      if (!fs.exists(new Path(basePath, currentWrittenFile + OFFSET_SUFFIX))) {
        fs.delete(new Path(basePath, String.valueOf(currentWrittenFile)), false);
      }

      if (fs.exists(new Path(basePath, flushedFileCounter + OFFSET_SUFFIX))) {
        // This means that flush was called
        flushedFileWriteOffset = getFlushedFileWriteOffset(new Path(basePath, flushedFileCounter + OFFSET_SUFFIX));
        bookKeepingFileOffset = getFlushedFileWriteOffset(new Path(basePath, flushedFileCounter + BOOK_KEEPING_FILE_OFFSET));
      }

      if (flushedFileCounter != -1) {
        currentWrittenFile = flushedFileCounter;
        fileWriteOffset = flushedFileWriteOffset;
      } else {
        currentWrittenFile = 0;
        fileWriteOffset = 0;
      }

      flushedLong = 0;

    } catch (IOException e) {
      closeFs();
      throw new RuntimeException(e);
    }
  }

  @Override
  public void flush()
  {
    StringBuilder builder = new StringBuilder(currentWrittenFile + "");
    Iterator<DataBlock> itr = files2Commit.iterator();
    DataBlock db;
    while (itr.hasNext()) {
      db = itr.next();
      db.updateOffsets();
      builder.append(", ").append(db.fileName);
    }
    logger.debug("flushed files {}", builder.toString());
    files2Commit.clear();

    if (dataStream != null) {
      try {
        dataStream.hflush();
        writeData(flushedCounterFile, String.valueOf(currentWrittenFile).getBytes()).close();
        updateFlushedOffset(new Path(basePath, currentWrittenFile + OFFSET_SUFFIX), fileWriteOffset);
        flushedFileWriteOffset = fileWriteOffset;
      } catch (IOException ex) {
        logger.warn("not able to close the stream {}", ex.getMessage());
        closeFs();
        throw new RuntimeException(ex);
      }
    }
    flushedFileCounter = currentWrittenFile;
    // logger.debug("flushedFileCounter in flush {}",flushedFileCounter);
  }

  /**
   * This updates the flushed offset
   */
  private void updateFlushedOffset(Path file, long bytesWritten)
  {
    byte[] lastStoredOffset = new byte[IDENTIFIER_SIZE];
    Server.writeLong(lastStoredOffset, 0, bytesWritten);
    try {
      writeData(file, lastStoredOffset).close();
    } catch (IOException e) {
      try {
        if (!Arrays.equals(readData(file), lastStoredOffset)) {
          closeFs();
          throw new RuntimeException(e);
        }
      } catch (Exception e1) {
        closeFs();
        throw new RuntimeException(e1);
      }      
    }
  }

  /**
   * @return the baseDir
   */
  public String getBaseDir()
  {
    return baseDir;
  }

  /**
   * @param baseDir
   *          the baseDir to set
   */
  public void setBaseDir(String baseDir)
  {
    this.baseDir = baseDir;
  }

  /**
   * @return the id
   */
  public String getId()
  {
    return id;
  }

  /**
   * @param id
   *          the id to set
   */
  public void setId(String id)
  {
    this.id = id;
  }

  /**
   * @return the blockSize
   */
  public long getBlockSize()
  {
    return blockSize;
  }

  /**
   * @param blockSize
   *          the blockSize to set
   */
  public void setBlockSize(long blockSize)
  {
    this.blockSize = blockSize;
  }

  /**
   * @return the restore
   */
  public boolean isRestore()
  {
    return restore;
  }

  /**
   * @param restore
   *          the restore to set
   */
  public void setRestore(boolean restore)
  {
    this.restore = restore;
  }

  class DataBlock
  {
    FSDataOutputStream dataStream;
    long dataOffset;
    Path path2FlushedData;
    long fileName;

    DataBlock(FSDataOutputStream stream, long bytesWritten, Path path2FlushedData, long fileName)
    {
      this.dataStream = stream;
      this.dataOffset = bytesWritten;
      this.path2FlushedData = path2FlushedData;
      this.fileName = fileName;
    }

    public void close()
    {
      if (dataStream != null) {
        try {
          dataStream.close();
          updateFlushedOffset(new Path(basePath, fileName + BOOK_KEEPING_FILE_OFFSET), dataOffset);
        } catch (IOException ex) {
          logger.warn("not able to close the stream {}", ex.getMessage());
          closeFs();
          throw new RuntimeException(ex);
        }
      }
    }

    public void updateOffsets()
    {
      updateFlushedOffset(path2FlushedData, dataOffset);
      // TODO: delete the book keeping file
    }

  }

  private static final Logger logger = LoggerFactory.getLogger(HDFSStorage.class);

  @Override
  public void setup(com.datatorrent.api.Context context)
  {
    // offset = ctx.getInteger(OFFSET_KEY, 4);
    Configuration conf = new Configuration();
    if (baseDir == null) {
      baseDir = conf.get("hadoop.tmp.dir");
      if (baseDir == null || baseDir.isEmpty()) {
        throw new IllegalArgumentException("baseDir cannot be null.");
      }
    }
    offset = 4;
    skipOffset = -1;
    skipFile = -1;

    try {
      Path path = new Path(baseDir);
      basePath = new Path(path, id);
      fs = FileSystem.newInstance(conf);
      if (!fs.exists(path)) {
        closeFs();
        throw new RuntimeException(String.format("baseDir passed (%s) doesn't exist.", baseDir));
      }
      if (!fs.isDirectory(path)) {
        closeFs();
        throw new RuntimeException(String.format("baseDir passed (%s) is not a directory.", baseDir));
      }
      if (!restore) {
        fs.delete(basePath, true);
      }
      if (!fs.exists(basePath) || !fs.isDirectory(basePath)) {
        fs.mkdirs(basePath);
      }

      if (blockSize == 0) {
        blockSize = fs.getDefaultBlockSize(new Path(basePath, "tempData"));
      }
      if (blockSize == 0) {
        blockSize = DEFAULT_BLOCK_SIZE;
      }

      currentWrittenFile = 0;
      cleanedFileCounter = -1;
      retrievalFile = -1;
      // fileCounterFile = new Path(basePath, IDENTITY_FILE);
      flushedFileCounter = -1;
      // cleanFileCounterFile = new Path(basePath, CLEAN_FILE);
      cleanFileOffsetFile = new Path(basePath, CLEAN_OFFSET_FILE);
      flushedCounterFile = new Path(basePath, FLUSHED_IDENTITY_FILE);
      if (restore) {
        //
        // if (fs.exists(fileCounterFile) && fs.isFile(fileCounterFile)) {
        // //currentWrittenFile = Long.valueOf(new String(readData(fileCounterFile)));
        // }

        if (fs.exists(cleanFileOffsetFile) && fs.isFile(cleanFileOffsetFile)) {
          cleanedOffset = readData(cleanFileOffsetFile);
        }

        if (fs.exists(flushedCounterFile) && fs.isFile(flushedCounterFile)) {
          String strFlushedFileCounter = new String(readData(flushedCounterFile));
          if (strFlushedFileCounter.isEmpty()) {
            logger.warn("empty flushed file");
          } else {
            flushedFileCounter = Long.valueOf(strFlushedFileCounter);
            flushedFileWriteOffset = getFlushedFileWriteOffset(new Path(basePath, flushedFileCounter + OFFSET_SUFFIX));
            bookKeepingFileOffset = getFlushedFileWriteOffset(new Path(basePath, flushedFileCounter + BOOK_KEEPING_FILE_OFFSET));
          }

        }
      }
      fileWriteOffset = flushedFileWriteOffset;
      currentWrittenFile = flushedFileCounter;
      cleanedFileCounter = byteArrayToLong(cleanedOffset, offset) - 1;
      if (currentWrittenFile == -1) {
        ++currentWrittenFile;
        fileWriteOffset = 0;
      }

    } catch (IOException io) {
      closeFs();
      throw new RuntimeException(io);
    }
  }
  
  private void closeFs(){
    if(fs != null){
      try {
        fs.close();
        fs = null;
      }
      catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }

  private long getFlushedFileWriteOffset(Path filePath) throws IOException
  {
    if (flushedFileCounter != -1 && fs.exists(filePath)) {
      byte[] flushedFileOffsetByte = readData(filePath);
      if (flushedFileOffsetByte != null) {
        return Server.readLong(flushedFileOffsetByte, 0);
      }
    }
    return 0;
  }

  @Override
  public void teardown()
  {

    try {
      if (readStream != null) {
        readStream.close();
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    } finally {
      closeUnflushedFiles();
    }

  }

}
