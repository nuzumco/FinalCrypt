/*
 * Copyright (C) 2018 Ron de Jong (ronuitzaandam@gmail.com).
 *
 * This is free software; you can redistribute it 
 * under the terms of the Creative Common License
 * Creative Common License: (CC BY-NC-ND 4.0) as published by
 * https://creativecommons.org/licenses/by-nc-nd/4.0/ ; either
 * version 4.0 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the 
 * Creative Commons Attribution-NonCommercial-NoDerivatives 4.0
 * International Public License for more details.
 *
 * You should have received a copy of the Creative Commons 
 * Public License License along with this software;
 */
package rdj;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.util.Duration;

public class Device
{
    int bufferSize = 1024 * 1024 * 1;
    long deviceSize = 0;
    long cipherSize = 0;
    static long bytesPerSector = 512;
    static UI ui;
    private static boolean pausing;
    private static boolean stopPending;
    
    public Device(UI ui)
    {
        this.ui = ui;
    }
    
//  Read byte[] from device
    synchronized public static byte[] read(Path rawDeviceFilePath, long lba, long length)
    {        
        long readInputDeviceChannelTransfered = 0;
        ByteBuffer inputDeviceBuffer = ByteBuffer.allocate((int)length); inputDeviceBuffer.clear();
        try (final SeekableByteChannel readInputDeviceChannel = Files.newByteChannel(rawDeviceFilePath, EnumSet.of(StandardOpenOption.READ)))
        {
            readInputDeviceChannel.position(getLBAOffSet(bytesPerSector, getDeviceSize(rawDeviceFilePath), lba));
            readInputDeviceChannelTransfered = readInputDeviceChannel.read(inputDeviceBuffer); inputDeviceBuffer.flip();
            readInputDeviceChannel.close();
            ui.log("Read LBA " + lba + " Transfered: " + readInputDeviceChannelTransfered + "\n");
        } catch (IOException ex) { ui.status("Device().read(..) " + ex.getMessage(), true); }
        return inputDeviceBuffer.array();
    }
    
//  Write byte[] to device
    synchronized public static void write(byte[] bytes, Path rawDeviceFilePath, long lba)
    {        
        long writeOutputDeviceChannelTransfered = 0;
        ByteBuffer outputDeviceBuffer = null;
        try (final SeekableByteChannel writeOutputDeviceChannel = Files.newByteChannel(rawDeviceFilePath, EnumSet.of(StandardOpenOption.WRITE, StandardOpenOption.SYNC)))
        {
            outputDeviceBuffer = ByteBuffer.allocate(bytes.length); outputDeviceBuffer.put(bytes); outputDeviceBuffer.flip(); // logBytes(outputDeviceBuffer.array());
//            guifx.log("\nBuffer: " + outputDeviceBuffer.capacity());
            writeOutputDeviceChannel.position(getLBAOffSet(bytesPerSector, getDeviceSize(rawDeviceFilePath), lba));
            writeOutputDeviceChannelTransfered = writeOutputDeviceChannel.write(outputDeviceBuffer);
            ui.log("Wrote LBA( " + lba + ")  Pos (" + getLBAOffSet(bytesPerSector, getDeviceSize(rawDeviceFilePath), lba) + ") Transfered: " + writeOutputDeviceChannelTransfered + "\n");
            writeOutputDeviceChannel.close();
        } catch (IOException ex) { ui.status(Arrays.toString(ex.getStackTrace()), true); }
    }

    
    
    
//  Write CipherFile to partition 1 & 2
    synchronized public void write(Path cipherFilePath, Path rawDeviceFilePath, long firstLBA1, long lastLBA1, long firstLBA2, long lastLBA2)
    {
        long deviceSize = getDeviceSize(rawDeviceFilePath);
        long cipherSize = getCipherSize(cipherFilePath);
        if ( cipherSize < bufferSize)   { bufferSize = (int)cipherSize; ui.log("BufferSize is limited to cipherfile size: " + GPT.getHumanSize(bufferSize, 1) + " \n"); }
//        else                            { log("BufferSize is set to: " + getHumanSize(bufferSize, 1) + " \n"); }
        Stats allDataStats = new Stats(); allDataStats.reset();        
        Stat readCipherFileStat1 = new Stat(); readCipherFileStat1.reset();
        Stat readCipherFileStat2 = new Stat(); readCipherFileStat2.reset();
        Stat writeCipherFileStat1 = new Stat(); writeCipherFileStat1.reset();
        Stat writeCipherFileStat2 = new Stat(); writeCipherFileStat2.reset();

        allDataStats.setFilesTotal(2);
        allDataStats.setAllDataBytesTotal(this.getCipherSize(cipherFilePath) * 2);
        ui.status(allDataStats.getStartSummary(), true);
        try { Thread.sleep(100); } catch (InterruptedException ex) {  }
        
        boolean inputEnded = false;
        long readCipherFileChannelPosition = 0;
        long readCipherFileChannelTransfered = 0;
        long writeOutputDeviceChannelPosition = 0;                
        long writeOutputDeviceChannelTransfered = 0;
        
//      Write the cipherfile to 1st partition
        ByteBuffer  cipherFileBuffer =      ByteBuffer.allocate(bufferSize); cipherFileBuffer.clear();
        byte[]      randomizedBytes =       new byte[bufferSize];
        ByteBuffer  randomizedBuffer =      ByteBuffer.allocate(bufferSize); cipherFileBuffer.clear();
        ByteBuffer  outputDeviceBuffer =    ByteBuffer.allocate(bufferSize); outputDeviceBuffer.clear();

//      Setup the Progress TIMER & TASK
        Timeline updateProgressTimeline = new Timeline(new KeyFrame( Duration.millis(200), ae ->
        ui.encryptionProgress
        (
                (int) (
                        (
                                readCipherFileStat1.getFileBytesProcessed() +
                                        writeCipherFileStat1.getFileBytesProcessed() +
                                        readCipherFileStat2.getFileBytesProcessed() +
                                        writeCipherFileStat2.getFileBytesProcessed()
                                ) / ( (allDataStats.getFileBytesTotal() * 1 ) / 100.0)),

                (int) (
                        ( allDataStats.getFilesBytesProcessed() * 4) /
                                ( (allDataStats.getFilesBytesTotal() * 4) / 100.0)
                        )
        )        ));
        updateProgressTimeline.setCycleCount(Animation.INDEFINITE); updateProgressTimeline.play();
        allDataStats.setAllDataStartNanoTime();

        ui.log("Writing " + cipherFilePath.toAbsolutePath() + " to partition 1 (LBA:"+ firstLBA1 + ":" + (getLBAOffSet(bytesPerSector, deviceSize, firstLBA1) + writeOutputDeviceChannelPosition) + ")");
        write1loop: while ( ! inputEnded )
        {
//            while (pausing)     { try { Thread.sleep(100); } catch (InterruptedException ex) {  } }
//            if (stopPending)    { inputEnded = true; break write1loop; }

            readCipherFileStat1.setFileStartEpoch();
            try (final SeekableByteChannel readCipherFileChannel = Files.newByteChannel(cipherFilePath, EnumSet.of(StandardOpenOption.READ)))
            {
                // Fill up cipherFileBuffer
                readCipherFileChannel.position(readCipherFileChannelPosition);
                readCipherFileChannelTransfered = readCipherFileChannel.read(cipherFileBuffer); readCipherFileChannelPosition += readCipherFileChannelTransfered;
                if (( readCipherFileChannelTransfered < 1 ) || ( cipherFileBuffer.limit() < bufferSize )) { inputEnded = true; }
                cipherFileBuffer.flip();
                readCipherFileChannel.close(); readCipherFileStat1.setFileEndEpoch(); readCipherFileStat1.clock();
                readCipherFileStat1.addFileBytesProcessed(readCipherFileChannelTransfered); allDataStats.addAllDataBytesProcessed(readCipherFileChannelTransfered);
            } catch (IOException ex) { ui.log("Files.newByteChannel(cipherFilePath, EnumSet.of(StandardOpenOption.READ)) " + ex + "\n"); }
            
//          Extra encrypt cipher randomly before writing to GPT partition
            SecureRandom random = new SecureRandom(); random.nextBytes(randomizedBytes);
            randomizedBuffer.put(randomizedBytes); randomizedBuffer.flip(); outputDeviceBuffer = encryptBuffer(cipherFileBuffer, randomizedBuffer);
            
//          Write Device
            writeCipherFileStat1.setFileStartEpoch();
            try (final SeekableByteChannel writeOutputDeviceChannel = Files.newByteChannel(rawDeviceFilePath, EnumSet.of(StandardOpenOption.WRITE,StandardOpenOption.SYNC)))
            {
//              Write cipherfile to partition 1
                writeOutputDeviceChannel.position((getLBAOffSet(bytesPerSector, deviceSize, firstLBA1) + writeOutputDeviceChannelPosition));
                writeOutputDeviceChannelTransfered = writeOutputDeviceChannel.write(outputDeviceBuffer); outputDeviceBuffer.rewind();
                writeCipherFileStat1.addFileBytesProcessed(writeOutputDeviceChannelTransfered); allDataStats.addAllDataBytesProcessed(readCipherFileChannelTransfered);
//                ui.log("\nwriteOutputDeviceChannelTransfered 1 : " + writeOutputDeviceChannelTransfered + "\n");
                
//              Write cipherfile to partition 2
                writeOutputDeviceChannel.position((getLBAOffSet(bytesPerSector, deviceSize, firstLBA2) + writeOutputDeviceChannelPosition));
                writeOutputDeviceChannelTransfered = writeOutputDeviceChannel.write(outputDeviceBuffer); outputDeviceBuffer.rewind();
                writeCipherFileStat2.addFileBytesProcessed(writeOutputDeviceChannelTransfered); allDataStats.addAllDataBytesProcessed(readCipherFileChannelTransfered);
//                ui.log("\nwriteOutputDeviceChannelTransfered 2 : " + writeOutputDeviceChannelTransfered + "\n");

                writeOutputDeviceChannelPosition += writeOutputDeviceChannelTransfered;

                if ( inputEnded )
                {
                    long partLength = ((lastLBA1 - firstLBA1) + 1) * bytesPerSector; long gap = partLength - cipherSize;
                    randomizedBytes = new byte[(int)gap]; random.nextBytes(randomizedBytes);
                    outputDeviceBuffer = ByteBuffer.allocate((int)gap); outputDeviceBuffer.clear(); outputDeviceBuffer.put(randomizedBytes); outputDeviceBuffer.flip();
                    
//                  Fill in gap to partition 1
                    writeOutputDeviceChannel.position((getLBAOffSet(bytesPerSector, deviceSize, firstLBA1) + writeOutputDeviceChannelPosition));
                    writeOutputDeviceChannelTransfered = writeOutputDeviceChannel.write(outputDeviceBuffer); outputDeviceBuffer.rewind();
                    writeCipherFileStat1.addFileBytesProcessed(writeOutputDeviceChannelTransfered); allDataStats.addAllDataBytesProcessed(readCipherFileChannelTransfered);
//                    ui.log("\nwriteOutputDeviceChannelTransfered 1 : " + writeOutputDeviceChannelTransfered + "\n");                

//                  Write cipherfile to partition 2
                    writeOutputDeviceChannel.position((getLBAOffSet(bytesPerSector, deviceSize, firstLBA2) + writeOutputDeviceChannelPosition));
                    writeOutputDeviceChannelTransfered = writeOutputDeviceChannel.write(outputDeviceBuffer); outputDeviceBuffer.rewind();
                    writeCipherFileStat2.addFileBytesProcessed(writeOutputDeviceChannelTransfered); allDataStats.addAllDataBytesProcessed(readCipherFileChannelTransfered);
//                    ui.log("\nwriteOutputDeviceChannelTransfered 2 : " + writeOutputDeviceChannelTransfered + "\n");
                }

                writeOutputDeviceChannel.close(); writeCipherFileStat1.setFileEndEpoch(); writeCipherFileStat1.clock();
            } catch (IOException ex) { ui.status(Arrays.toString(ex.getStackTrace()), true); }
            cipherFileBuffer.clear(); randomizedBuffer.clear(); outputDeviceBuffer.clear();
        }
        readCipherFileChannelPosition = 0;
        readCipherFileChannelTransfered = 0;
        writeOutputDeviceChannelPosition = 0;                
        writeOutputDeviceChannelTransfered = 0;                
        inputEnded = false;

//      FILE STATUS        
        ui.log(" - Write: rd(" +  readCipherFileStat1.getFileBytesThroughPut() + ") -> ");
        ui.log("wr(" +           writeCipherFileStat1.getFileBytesThroughPut() + ") ");
        ui.log(" - Write: rd(" +  readCipherFileStat2.getFileBytesThroughPut() + ") -> ");
        ui.log("wr(" +           writeCipherFileStat2.getFileBytesThroughPut() + ") ");
        ui.log(allDataStats.getAllDataBytesProgressPercentage());


        allDataStats.addFilesProcessed(2);
        allDataStats.setAllDataEndNanoTime(); allDataStats.clock();

//        if ( stopPending ) { ui.status("\n", false); stopPending = false;  } // It breaks in the middle of encrypting, so the encryption summery needs to begin on a new line
        ui.status(allDataStats.getEndSummary(), true);

        updateProgressTimeline.stop();
        ui.encryptionFinished();
    }

    private ByteBuffer encryptBuffer(ByteBuffer inputFileBuffer, ByteBuffer cipherFileBuffer)
    {
        int inputTotal = 0;
        int cipherTotal = 0;
        int outputDiff = 0;
        byte inputByte = 0;
        byte cipherByte = 0;
        byte outputByte;
        
        ByteBuffer outputFileBuffer =   ByteBuffer.allocate(bufferSize); outputFileBuffer.clear();
        while (pausing)     { try { Thread.sleep(100); } catch (InterruptedException ex) {  } }
        for (int inputFileBufferCount = 0; inputFileBufferCount < inputFileBuffer.limit(); inputFileBufferCount++)
        {
            inputTotal += inputByte;
            cipherTotal += cipherByte;
            inputByte = inputFileBuffer.get(inputFileBufferCount);
            cipherByte = cipherFileBuffer.get(inputFileBufferCount);
            outputByte = encryptByte(inputFileBuffer.get(inputFileBufferCount), cipherFileBuffer.get(inputFileBufferCount));
            outputFileBuffer.put(outputByte);
        }
        outputFileBuffer.flip();
        // MD5Sum dataTotal XOR MD5Sum cipherTotal (Diff dataTot and cipherTot) 32 bit 4G
        
        outputDiff = inputTotal ^ cipherTotal;
        
//        if (debug)
//        {
//            ui.log(Integer.toString(inputTotal) + "\n");
//            ui.log(Integer.toString(cipherTotal) + "\n");
//            ui.log(Integer.toString(outputDiff) + "\n");
//        MD5Converter.getMD5SumFromString(Integer.toString(dataTotal));
//        MD5Converter.getMD5SumFromString(Integer.toString(cipherTotal));
//        }
        
        return outputFileBuffer;
    }
    
    private byte encryptByte(final byte dataByte, byte cipherByte)
    {
        int dum = 0;  // DUM Data Unnegated Mask
        int dnm = 0;  // DNM Data Negated Mask
        int dbm = 0;  // DBM Data Blended Mask
        byte outputByte;
        
//      Negate 0 cipherbytes to prevent 0 encryption
        if (cipherByte == 0) { cipherByte = (byte)(~cipherByte & 0xFF); }

        dum = dataByte & ~cipherByte;
        dnm = ~dataByte & cipherByte;
        dbm = dum + dnm; // outputByte        
        outputByte = (byte)(dbm & 0xFF);
        
        // Increment Byte Progress Counters        
        return (byte)dbm; // outputByte
    }

    synchronized private long getCipherSize(Path cipherFilePath)
    {
        long cipherSize = 0;
        try { cipherSize = (int)Files.size(cipherFilePath); } catch (IOException ex) { ui.log("Files.size(finalCrypt.getCipherFilePath()) " + ex + "\n"); }
        return cipherSize;
    }

//  Get size of device        
    synchronized private static long getDeviceSize(Path rawDeviceFilePath)
    {
        long deviceSize = 0;
        try (final SeekableByteChannel deviceChannel = Files.newByteChannel(rawDeviceFilePath, EnumSet.of(StandardOpenOption.READ))) { deviceSize = deviceChannel.size(); deviceChannel.close(); }
        catch (IOException ex) { ui.status(ex.getMessage(), true); }
        
        return deviceSize;
    }

    synchronized private static long getLBAOffSet(long bytesPerSector, long devSize, long lba)
    {
        if ( lba >= 0 )
        {
            long returnValue = 0; returnValue = (lba * bytesPerSector);
//            guifx.log("LBA: " + logicalBlockAddress + " Pos: " + returnValue); guifx.log("\n");
            return returnValue;
        }
        else
        {
            long returnValue = 0; returnValue = ((devSize - 0) + (lba * bytesPerSector)); // -1 from size to 0 start position
//            guifx.log("LBA: " + logicalBlockAddress + " Pos: " + returnValue); guifx.log("\n");
            return returnValue;
        }
    }
    
    public static boolean getPausing()             { return pausing; }
    public static boolean getStopPending()         { return stopPending; }
    public static void setPausing(boolean val)     { pausing = val; }
    public static void setStopPending(boolean val) { stopPending = val; }
}