package com.orco.holder.util.logging


import java.io.{File, FileOutputStream, IOException, InputStream}

import com.orco.holder.HolderConf
import com.orco.holder.internal.Logging
import com.orco.holder.util.{IntParam, Utils}

/**
  * Continuously appends the data from an input stream into the given file.
  */
private[holder] class FileAppender(inputStream: InputStream, file: File, bufferSize: Int = 8192)
  extends Logging {
  @volatile private var outputStream: FileOutputStream = null
  @volatile private var markedForStop = false     // has the appender been asked to stopped

  // Thread that reads the input stream and writes to file
  private val writingThread = new Thread("File appending thread for " + file) {
    setDaemon(true)
    override def run() {
      Utils.logUncaughtExceptions {
        appendStreamToFile()
      }
    }
  }
  writingThread.start()

  /**
    * Wait for the appender to stop appending, either because input stream is closed
    * or because of any error in appending
    */
  def awaitTermination() {
    writingThread.join()
  }

  /** Stop the appender */
  def stop() {
    markedForStop = true
  }

  /** Continuously read chunks from the input stream and append to the file */
  protected def appendStreamToFile() {
    try {
      logDebug("Started appending thread")
      Utils.tryWithSafeFinally {
        openFile()
        val buf = new Array[Byte](bufferSize)
        var n = 0
        while (!markedForStop && n != -1) {
          try {
            n = inputStream.read(buf)
          } catch {
            // An InputStream can throw IOException during read if the stream is closed
            // asynchronously, so once appender has been flagged to stop these will be ignored
            case _: IOException if markedForStop =>  // do nothing and proceed to stop appending
          }
          if (n > 0) {
            appendToFile(buf, n)
          }
        }
      } {
        closeFile()
      }
    } catch {
      case e: Exception =>
        logError(s"Error writing stream to file $file", e)
    }
  }

  /** Append bytes to the file output stream */
  protected def appendToFile(bytes: Array[Byte], len: Int) {
    if (outputStream == null) {
      openFile()
    }
    outputStream.write(bytes, 0, len)
  }

  /** Open the file output stream */
  protected def openFile() {
    outputStream = new FileOutputStream(file, true)
    logDebug(s"Opened file $file")
  }

  /** Close the file output stream */
  protected def closeFile() {
    outputStream.flush()
    outputStream.close()
    logDebug(s"Closed file $file")
  }
}

/**
  * Companion object to [org.apache.holder.util.logging.FileAppender which has helper
  * functions to choose the correct type of FileAppender based on HolderConf configuration.
  */
private[holder] object FileAppender extends Logging {

  /** Create the right appender based on holder configuration */
  def apply(inputStream: InputStream, file: File, conf: HolderConf): FileAppender = {

    import RollingFileAppender._

    val rollingStrategy = conf.get(STRATEGY_PROPERTY, STRATEGY_DEFAULT)
    val rollingSizeBytes = conf.get(SIZE_PROPERTY, STRATEGY_DEFAULT)
    val rollingInterval = conf.get(INTERVAL_PROPERTY, INTERVAL_DEFAULT)

    def createTimeBasedAppender(): FileAppender = {
      val validatedParams: Option[(Long, String)] = rollingInterval match {
        case "daily" =>
          logInfo(s"Rolling executor logs enabled for $file with daily rolling")
          Some((24 * 60 * 60 * 1000L, "--yyyy-MM-dd"))
        case "hourly" =>
          logInfo(s"Rolling executor logs enabled for $file with hourly rolling")
          Some((60 * 60 * 1000L, "--yyyy-MM-dd--HH"))
        case "minutely" =>
          logInfo(s"Rolling executor logs enabled for $file with rolling every minute")
          Some((60 * 1000L, "--yyyy-MM-dd--HH-mm"))
        case IntParam(seconds) =>
          logInfo(s"Rolling executor logs enabled for $file with rolling $seconds seconds")
          Some((seconds * 1000L, "--yyyy-MM-dd--HH-mm-ss"))
        case _ =>
          logWarning(s"Illegal interval for rolling executor logs [$rollingInterval], " +
            s"rolling logs not enabled")
          None
      }
      validatedParams.map {
        case (interval, pattern) =>
          new RollingFileAppender(
            inputStream, file, new TimeBasedRollingPolicy(interval, pattern), conf)
      }.getOrElse {
        new FileAppender(inputStream, file)
      }
    }

    def createSizeBasedAppender(): FileAppender = {
      rollingSizeBytes match {
        case IntParam(bytes) =>
          logInfo(s"Rolling executor logs enabled for $file with rolling every $bytes bytes")
          new RollingFileAppender(inputStream, file, new SizeBasedRollingPolicy(bytes), conf)
        case _ =>
          logWarning(
            s"Illegal size [$rollingSizeBytes] for rolling executor logs, rolling logs not enabled")
          new FileAppender(inputStream, file)
      }
    }

    rollingStrategy match {
      case "" =>
        new FileAppender(inputStream, file)
      case "time" =>
        createTimeBasedAppender()
      case "size" =>
        createSizeBasedAppender()
      case _ =>
        logWarning(
          s"Illegal strategy [$rollingStrategy] for rolling executor logs, " +
            s"rolling logs not enabled")
        new FileAppender(inputStream, file)
    }
  }
}