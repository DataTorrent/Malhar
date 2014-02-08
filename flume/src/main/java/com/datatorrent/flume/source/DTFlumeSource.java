/*
 *  Copyright (c) 2012-2013 Malhar, Inc.
 *  All Rights Reserved.
 */
package com.datatorrent.flume.source;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import javax.annotation.Nonnull;

import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.EventDrivenSource;
import org.apache.flume.channel.ChannelProcessor;
import org.apache.flume.conf.Configurable;
import org.apache.flume.event.EventBuilder;
import org.apache.flume.source.AbstractSource;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.collect.Lists;

public class DTFlumeSource extends AbstractSource implements EventDrivenSource, Configurable
{
  static String FILE_NAME = "sourceFile";
  static String RATE = "rate";
  static byte FIELD_SEPARATOR = 1;
  private static String d1 = "2013-11-07";

  public Timer emitTimer;

  @Nonnull
  public String filePath;
  public int rate;
  public transient String yesterdayDate;
  public transient String todayDate;

  private transient BufferedReader lineReader;
  private transient List<Event> cache;

  public DTFlumeSource()
  {
    super();
    final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    Calendar cal = Calendar.getInstance();
    todayDate = dateFormat.format(cal.getTimeInMillis());

    cal.add(Calendar.DATE, -1);
    yesterdayDate = dateFormat.format(cal.getTimeInMillis());
    rate = -1;

  }

  @Override
  public void configure(Context context)
  {
    filePath = Preconditions.checkNotNull(context.getString(FILE_NAME));
    rate = context.getInteger(RATE);
    emitTimer = new Timer();
    Preconditions.checkArgument(!Strings.isNullOrEmpty(filePath));
    cache = Lists.newArrayListWithCapacity(rate);
    try {
      lineReader = new BufferedReader(new InputStreamReader(new FileInputStream(filePath)));
      for (int i = 0; i < rate; i++) {
        String line = lineReader.readLine();
        if (line == null) {
          lineReader.close();
          lineReader = new BufferedReader(new InputStreamReader(new FileInputStream(filePath)));
          line = lineReader.readLine();
        }
        byte[] row = line.getBytes();
        final int rowsize = row.length;

            /* guid */
        int sliceLengh = -1;
        while (++sliceLengh < rowsize) {
          if (row[sliceLengh] == FIELD_SEPARATOR) {
            break;
          }
        }
            /* skip the next record */
        int pointer = sliceLengh + 1;
        while (pointer < rowsize) {
          if (row[pointer++] == FIELD_SEPARATOR) {
            break;
          }
        }

           /* lets parse the date */
        int dateStart = pointer;
        while (pointer < rowsize) {
          if (row[pointer++] == FIELD_SEPARATOR) {
            String date = new String(row, dateStart, pointer - dateStart - 1);
            if (date.indexOf(d1) >= 0) {
              System.arraycopy(yesterdayDate.getBytes(), 0, row, dateStart, yesterdayDate.getBytes().length);
            }
            else {
              System.arraycopy(todayDate.getBytes(), 0, row, dateStart, todayDate.getBytes().length);
            }
            break;
          }
        }
        Event event = EventBuilder.withBody(row);
        cache.add(event);
      }
    }
    catch (Throwable t) {
      Throwables.propagate(t);
    }

  }

  @Override
  public void start()
  {
    final ChannelProcessor channel = getChannelProcessor();
    emitTimer.scheduleAtFixedRate(new TimerTask()
    {
      @Override
      public void run()
      {
        for (Event event : cache) {
          channel.processEvent(event);
        }
      }
    }, 0, 1000);
  }

  @Override
  public void stop()
  {
    emitTimer.cancel();
    super.stop();
  }
}
