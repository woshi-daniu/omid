/**
 * Copyright (c) 2011 Yahoo! Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. See accompanying LICENSE file.
 */

package com.yahoo.omid.tso;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.Channels;

public class TSOSharedMessageBuffer {

   private static final Log LOG = LogFactory.getLog(TSOSharedMessageBuffer.class);
   
//   private ChannelBuffer readBuffer;
   
   private int readerIndex = 0;
//   private TSOBuffer readingBuffer;
   private TSOState state;
   
   TSOBuffer pastBuffer = new TSOBuffer();
   TSOBuffer currentBuffer = new TSOBuffer();
   ChannelBuffer writeBuffer = currentBuffer.buffer;
//   TSOBuffer readingBuffer;
   Deque<TSOBuffer> futureBuffer = new ArrayDeque<TSOBuffer>();
   
   class ReadingBuffer implements Comparable<ReadingBuffer> {
       private ChannelBuffer readBuffer;
       private int readerIndex = 0;
       private TSOBuffer readingBuffer;
       private Channel channel;
       
       public ReadingBuffer(Channel channel) {
           this.channel = channel;
           this.readingBuffer = currentBuffer.reading(this);
           this.readBuffer = readingBuffer.buffer;
           this.readerIndex = readBuffer.writerIndex();
       }

       public void flush() {
          flush(true, false);
       }
       
//       static long _flushes = 0;
//       static long _flSize = 0;
    //   byte dummy [] = new byte [1100];
//       ChannelBuffer dummyBuf = ChannelBuffers.directBuffer(1100);
       
    //   static public Map<Integer,Long> lastTime = new java.util.HashMap<Integer, Long>();
//       static public double _avgLT = 0;
       
       private void flush(boolean deleteRef, final boolean clearPast) {
          int readable = readBuffer.readableBytes() - readerIndex;

          
          if (readable == 0 && readingBuffer != pastBuffer) {
              _emptyFlushes++;
              if (!wrap) {
                  return;
              }
//              Long _lTL = lastTime.get(channel.getId());
//              long _lastTime = _lTL == null ? 0 : _lTL; 
//              long now =_flushes + _emptyFlushes;
//              _avgLT += ((now - _lastTime) - _avgLT) / _emptyFlushes;
//             return;
          }
//          ++_flushes;
//          lastTime.put(channel.getId(), _flushes + _emptyFlushes);
          
          ChannelBuffer temp;
          if (wrap && readingBuffer != pastBuffer) {
              temp = ChannelBuffers.wrappedBuffer(readBuffer.slice(readerIndex, readable), tBuffer);  
          } else {
              temp = readBuffer.slice(readerIndex, readable);
          }
          ChannelFuture future = Channels.write(channel, temp);
          readerIndex += readable;
//          _flSize += readable;
          if (readingBuffer == pastBuffer) {
             readingBuffer = currentBuffer.reading(this);
             readBuffer = readingBuffer.buffer;
             readerIndex = 0;
             readable = readBuffer.readableBytes();
             if (wrap) {
                 temp = ChannelBuffers.wrappedBuffer(readBuffer.slice(readerIndex, readable), tBuffer);
             } else {
                 temp = readBuffer.slice(readerIndex, readable);
             }
             Channels.write(channel, temp);
             readerIndex += readable;
//             _flSize += readable;
             if (deleteRef) {
                 pastBuffer.readingBuffers.remove(this);
             }
             pastBuffer.incrementPending();
             final TSOBuffer pendingBuffer = pastBuffer;
             future.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                   if (clearPast) {
                      pendingBuffer.readingBuffers.clear();
                   }
                   if (pendingBuffer.decrementPending() && pendingBuffer.readingBuffers.size() == 0) {
                      pendingBuffer.buffer.clear();
                      synchronized (futureBuffer) {
                          futureBuffer.add(pendingBuffer);
                      }
                   }
                }
             });
          }
          
       }
       
       @Override
       public int compareTo(ReadingBuffer o) {
          return this.channel.compareTo(o.channel);
       }
       
       @Override
       public boolean equals(Object obj) {
          if (!(obj instanceof ReadingBuffer))
             return false;
          ReadingBuffer buf = (ReadingBuffer) obj;
          return this.channel.equals(buf.channel);
       }

   }
   
   public TSOSharedMessageBuffer(TSOState state) {
//      this.channel = channel;
      this.state = state;
//      this.readingBuffer = currentBuffer.reading(this);
//      this.readBuffer = readingBuffer.buffer;
      this.readerIndex = writeBuffer.writerIndex();
   }

   static private final byte commitDiffs [] = new byte [] { 0, 0x00, 0x20, 0x40, 0x60 };
   static private final byte m0x80 = (byte) 0x80;
   static private final byte m0x3f = (byte) 0x3f;
   static private final byte m0x0f = (byte) 0x0f;
   static private final byte m0xff = (byte) 0xff;
   static private final long mLong = ~(0x7f);

   static long _1B = 0;
   static long _2B = 0;
   static long _AB = 0;
   static long _AS = 0;
   static long _LL = 0;
   static long _Coms = 0;
   static long _Writes = 0;
   static double _Avg = 0;
   static double _Avg2 = 0;
   
   
   static long _ha = 0;
   static long _fa = 0;
   static long _li = 0;
   
   static long _overflows = 0;
   static long _emptyFlushes = 0;
   

   private ChannelBuffer tBuffer;
   private boolean wrap = false;
   
   public void writeTimestamp(long timestamp) {
       wrap = true;
       tBuffer = ChannelBuffers.buffer(9);
       tBuffer.writeByte(TSOMessage.TimestampResponse);
       tBuffer.writeLong(timestamp);
   }
   
   public void rollBackTimestamp() {
       wrap = false;
   }

   public void writeCommit(long startTimestamp, long commitTimestamp) {
      if (writeBuffer.writableBytes() < 30) {
         nextBuffer();
      }
      ++_Coms;
      long startDiff = startTimestamp - state.latestStartTimestamp;
      long commitDiff = commitTimestamp - state.latestCommitTimestamp;
      ++_Writes;
      int readBefore = writeBuffer.readableBytes();
      if (commitDiff == 1 && startDiff >= -64 && startDiff <= 63) {
        ++_1B;
        startDiff &= 0x7f;
        writeBuffer.writeByte((byte) startDiff);
//        System.out.println("1 Byte: " + (byte)startDiff);
    } else if (commitDiff == 1 && startDiff >= -8192 && startDiff <= 8191) {
          ++_2B;
          byte high = m0x80;
          high |= (startDiff >> 8) & m0x3f; 
          byte low = (byte) (startDiff & m0xff);
          writeBuffer.writeByte(high);
          writeBuffer.writeByte(low);
//          System.out.println("2 Bytes: " + high + " " + low);
      } else if (commitDiff >= Byte.MIN_VALUE && commitDiff <= Byte.MAX_VALUE) {
//          System.out.println("____Byte");
          if (startDiff >= Byte.MIN_VALUE && startDiff <= Byte.MAX_VALUE) {
              ++_AB;
              writeBuffer.writeByte(TSOMessage.CommittedTransactionReportByteByte);
              writeBuffer.writeByte((byte) startDiff);
          } else if (startDiff >= Short.MIN_VALUE && startDiff <= Short.MAX_VALUE) {
              ++_AS;
              writeBuffer.writeByte(TSOMessage.CommittedTransactionReportShortByte);
              writeBuffer.writeShort((short) startDiff);
          } else if (startDiff >= Integer.MIN_VALUE && startDiff <= Integer.MAX_VALUE) {
              ++_LL;
              writeBuffer.writeByte(TSOMessage.CommittedTransactionReportIntegerByte);
              writeBuffer.writeInt((int) startDiff);
          } else {
              writeBuffer.writeByte(TSOMessage.CommittedTransactionReportLongByte);
              writeBuffer.writeLong((byte) startDiff);
          }
          writeBuffer.writeByte((byte) commitDiff);
      }  else if (commitDiff >= Short.MIN_VALUE && commitDiff <= Short.MAX_VALUE) {
          ++_AS;
//          System.out.println("____Short");
          if (startDiff >= Byte.MIN_VALUE && startDiff <= Byte.MAX_VALUE) {
              writeBuffer.writeByte(TSOMessage.CommittedTransactionReportByteShort);
              writeBuffer.writeByte((byte) startDiff);
          } else if (startDiff >= Short.MIN_VALUE && startDiff <= Short.MAX_VALUE) {
              writeBuffer.writeByte(TSOMessage.CommittedTransactionReportShortShort);
              writeBuffer.writeShort((short) startDiff);
          } else if (startDiff >= Integer.MIN_VALUE && startDiff <= Integer.MAX_VALUE) {
              writeBuffer.writeByte(TSOMessage.CommittedTransactionReportIntegerShort);
              writeBuffer.writeInt((int) startDiff);
          } else {
              writeBuffer.writeByte(TSOMessage.CommittedTransactionReportLongShort);
              writeBuffer.writeLong((byte) startDiff);
          }
          writeBuffer.writeShort((short) commitDiff);
      } else {
          ++_LL;
//          System.out.println("LongLong");
          writeBuffer.writeByte(TSOMessage.CommittedTransactionReport);
          writeBuffer.writeLong(startTimestamp);
          writeBuffer.writeLong(commitTimestamp);
       }
      int written = writeBuffer.readableBytes() - readBefore;
      _Avg2 += (written - _Avg2) / _Writes;
      _Avg += (written - _Avg) / _Coms;
      state.latestStartTimestamp = startTimestamp;
      state.latestCommitTimestamp = commitTimestamp;
//      System.out.println("Encoded " + startTimestamp + " " + commitTimestamp);
   }

   public void writeHalfAbort(long startTimestamp) {
      if (writeBuffer.writableBytes() < 30) {
         nextBuffer();
      }
      ++_Writes;
      int readBefore = writeBuffer.readableBytes();
      
      long diff = startTimestamp - state.latestHalfAbortTimestamp;
      if (diff >= Byte.MIN_VALUE && diff <= Byte.MAX_VALUE) {
          writeBuffer.writeByte(TSOMessage.AbortedTransactionReportByte);
          writeBuffer.writeByte((byte)diff);
//          System.out.println("Encoded half abort " + startTimestamp + " Diff: "+ diff);
      } else {
          writeBuffer.writeByte(TSOMessage.AbortedTransactionReport);
          writeBuffer.writeLong(startTimestamp);
          ++_ha;
//          System.out.println("Encoded half abort " + startTimestamp);
      }
      
      state.latestHalfAbortTimestamp = startTimestamp;

      
      int written = writeBuffer.readableBytes() - readBefore;
      _Avg2 += (written - _Avg2) / _Writes;
   }
   
   public void writeFullAbort(long startTimestamp) {
      if (writeBuffer.writableBytes() < 30) {
         nextBuffer();
      }
      ++_Writes;
      int readBefore = writeBuffer.readableBytes();
      
      
      long diff = startTimestamp - state.latestFullAbortTimestamp;
      if (diff >= Byte.MIN_VALUE && diff <= Byte.MAX_VALUE) {
          writeBuffer.writeByte(TSOMessage.FullAbortReportByte);
          writeBuffer.writeByte((byte)diff);
//          System.out.println("Encoded full abort " + startTimestamp + " Diff: " + diff);
      } else {
          writeBuffer.writeByte(TSOMessage.FullAbortReport);
          writeBuffer.writeLong(startTimestamp);
          ++_fa;
//          System.out.println("Encoded full abort " + startTimestamp);
      }
      
      state.latestFullAbortTimestamp = startTimestamp;

      
      int written = writeBuffer.readableBytes() - readBefore;
      _Avg2 += (written - _Avg2) / _Writes;
   }
   
   public void writeLargestIncrease(long largestTimestamp) {
      if (writeBuffer.writableBytes() < 30) {
         nextBuffer();
      }
      ++_li;
      ++_Writes;
      int readBefore = writeBuffer.readableBytes();
      writeBuffer.writeByte(TSOMessage.LargestDeletedTimestampReport);
      writeBuffer.writeLong(largestTimestamp);
      int written = writeBuffer.readableBytes() - readBefore;
      _Avg2 += (written - _Avg2) / _Writes;
   }
   
   private void nextBuffer() {      
      LOG.debug("Switching buffers");
      
      _overflows++;
      
      Iterator<ReadingBuffer> it = pastBuffer.readingBuffers.iterator();
      boolean moreBuffers = it.hasNext();
      while(moreBuffers) {
         ReadingBuffer buf = it.next();
         moreBuffers = it.hasNext();
         buf.flush(false, !moreBuffers);
      }
      
//      TSOBuffer t = pastBuffer;
      pastBuffer = currentBuffer;
      currentBuffer = null;
//      currentBuffer.buffer.clear();
      synchronized (futureBuffer) {
         if (!futureBuffer.isEmpty()) {
             currentBuffer = futureBuffer.removeLast();
         }
      }
      if (currentBuffer == null) {
          currentBuffer = new TSOBuffer();
      }
      writeBuffer = currentBuffer.buffer;
   }
//   
//   public void flush() {
//      flush(true, false);
//   }
//   
//
//   static long _flushes = 0;
//   static long _flSize = 0;
////   byte dummy [] = new byte [1100];
//   ChannelBuffer dummyBuf = ChannelBuffers.directBuffer(1100);
//   
////   static public Map<Integer,Long> lastTime = new java.util.HashMap<Integer, Long>();
//   static public double _avgLT = 0;
//   
//   private void flush(boolean deleteRef, final boolean clearPast) {
//      int readable = readBuffer.readableBytes() - readerIndex;
//
//      
//      if (readable == 0 && readingBuffer != pastBuffer) {
//          _emptyFlushes++;
//          if (!wrap) {
//              return;
//          }
////          Long _lTL = lastTime.get(channel.getId());
////          long _lastTime = _lTL == null ? 0 : _lTL; 
////          long now =_flushes + _emptyFlushes;
////          _avgLT += ((now - _lastTime) - _avgLT) / _emptyFlushes;
////         return;
//      }
//      ++_flushes;
////      lastTime.put(channel.getId(), _flushes + _emptyFlushes);
//      
//      ChannelBuffer temp;
//      if (wrap && readingBuffer != pastBuffer) {
//          temp = ChannelBuffers.wrappedBuffer(readBuffer.slice(readerIndex, readable), tBuffer);  
//      } else {
//          temp = readBuffer.slice(readerIndex, readable);
//      }
//      ChannelFuture future = Channels.write(channel, temp);
//      readerIndex += readable;
//      _flSize += readable;
//      if (readingBuffer == pastBuffer) {
//         readingBuffer = currentBuffer.reading(this);
//         readBuffer = readingBuffer.buffer;
//         readerIndex = 0;
//         readable = readBuffer.readableBytes();
//         if (wrap) {
//             temp = ChannelBuffers.wrappedBuffer(readBuffer.slice(readerIndex, readable), tBuffer);
//         } else {
//             temp = readBuffer.slice(readerIndex, readable);
//         }
//         Channels.write(channel, temp);
//         readerIndex += readable;
//         _flSize += readable;
//         if (deleteRef) {
//             pastBuffer.readingBuffers.remove(this);
//         }
//         pastBuffer.incrementPending();
//         final TSOBuffer pendingBuffer = pastBuffer;
//         future.addListener(new ChannelFutureListener() {
//            @Override
//            public void operationComplete(ChannelFuture future) throws Exception {
//               if (clearPast) {
//                  pendingBuffer.readingBuffers.clear();
//               }
//               if (pendingBuffer.decrementPending() && pendingBuffer.readingBuffers.size() == 0) {
//                  pendingBuffer.buffer.clear();
//                  synchronized (futureBuffer) {
//                      futureBuffer.add(pendingBuffer);
//                  }
//               }
//            }
//         });
//      }
//   }

   
   public void reset() {
      if (pastBuffer != null) {
         pastBuffer.readingBuffers.clear();
         pastBuffer.buffer.clear();
      }
      if (currentBuffer != null) {
         currentBuffer.readingBuffers.clear();
         currentBuffer.buffer.clear();
      }
   }
}

