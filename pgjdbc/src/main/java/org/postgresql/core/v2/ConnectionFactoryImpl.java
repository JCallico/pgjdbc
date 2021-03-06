/*-------------------------------------------------------------------------
*
* Copyright (c) 2003-2014, PostgreSQL Global Development Group
* Copyright (c) 2004, Open Cloud Limited.
*
*
*-------------------------------------------------------------------------
*/

package org.postgresql.core.v2;

import org.postgresql.PGProperty;
import org.postgresql.core.ConnectionFactory;
import org.postgresql.core.Encoding;
import org.postgresql.core.Logger;
import org.postgresql.core.PGStream;
import org.postgresql.core.ProtocolConnection;
import org.postgresql.core.SetupQueryRunner;
import org.postgresql.core.Utils;
import org.postgresql.hostchooser.GlobalHostStatusTracker;
import org.postgresql.hostchooser.HostChooser;
import org.postgresql.hostchooser.HostChooserFactory;
import org.postgresql.hostchooser.HostRequirement;
import org.postgresql.hostchooser.HostStatus;
import org.postgresql.util.GT;
import org.postgresql.util.HostSpec;
import org.postgresql.util.MD5Digest;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.UnixCrypt;

import java.io.IOException;
import java.net.ConnectException;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.Iterator;
import java.util.Properties;
import java.util.StringTokenizer;

import javax.net.SocketFactory;

/**
 * ConnectionFactory implementation for version 2 (pre-7.4) connections.
 *
 * @author Oliver Jowett (oliver@opencloud.com), based on the previous implementation
 */
public class ConnectionFactoryImpl extends ConnectionFactory {
  private static final int AUTH_REQ_OK = 0;
  private static final int AUTH_REQ_KRB4 = 1;
  private static final int AUTH_REQ_KRB5 = 2;
  private static final int AUTH_REQ_PASSWORD = 3;
  private static final int AUTH_REQ_CRYPT = 4;
  private static final int AUTH_REQ_MD5 = 5;
  private static final int AUTH_REQ_SCM = 6;

  public ProtocolConnection openConnectionImpl(HostSpec[] hostSpecs, String user, String database,
      Properties info, Logger logger) throws SQLException {
    // Extract interesting values from the info properties:
    // - the SSL setting
    boolean requireSSL;
    boolean trySSL;
    String sslmode = PGProperty.SSL_MODE.get(info);
    if (sslmode == null) { // Fall back to the ssl property
      // assume "true" if the property is set but empty
      requireSSL = trySSL = PGProperty.SSL.getBoolean(info) || "".equals(PGProperty.SSL.get(info));
    } else {
      if ("disable".equals(sslmode)) {
        requireSSL = trySSL = false;
      } else if ("require".equals(sslmode) || "verify-ca".equals(sslmode)
          || "verify-full".equals(sslmode)) {
        requireSSL = trySSL = true;
      } else {
        throw new PSQLException(GT.tr("Invalid sslmode value: {0}", sslmode),
            PSQLState.CONNECTION_UNABLE_TO_CONNECT);
      }
    }

    // - the TCP keep alive setting
    boolean requireTCPKeepAlive = PGProperty.TCP_KEEP_ALIVE.getBoolean(info);

    // - the targetServerType setting
    HostRequirement targetServerType;
    try {
      targetServerType =
          HostRequirement.valueOf(info.getProperty("targetServerType", HostRequirement.any.name()));
    } catch (IllegalArgumentException ex) {
      throw new PSQLException(
          GT.tr("Invalid targetServerType value: {0}", info.getProperty("targetServerType")),
          PSQLState.CONNECTION_UNABLE_TO_CONNECT);
    }

    // Socket factory
    SocketFactory socketFactory = SocketFactoryFactory.getSocketFactory(info);

    HostChooser hostChooser =
        HostChooserFactory.createHostChooser(hostSpecs, targetServerType, info);
    Iterator<HostSpec> hostIter = hostChooser.iterator();
    while (hostIter.hasNext()) {
      HostSpec hostSpec = hostIter.next();

      if (logger.logDebug()) {
        logger.debug("Trying to establish a protocol version 2 connection to " + hostSpec);
      }

      //
      // Establish a connection.
      //
      int connectTimeout = PGProperty.CONNECT_TIMEOUT.getInt(info) * 1000;

      PGStream newStream = null;
      try {
        newStream = new PGStream(socketFactory, hostSpec, connectTimeout);

        // Construct and send an ssl startup packet if requested.
        if (trySSL) {
          newStream = enableSSL(newStream, requireSSL, info, logger, connectTimeout);
        }


        // Set the socket timeout if the "socketTimeout" property has been set.
        int socketTimeout = PGProperty.SOCKET_TIMEOUT.getInt(info);
        if (socketTimeout > 0) {
          newStream.getSocket().setSoTimeout(socketTimeout * 1000);
        }

        // Enable TCP keep-alive probe if required.
        newStream.getSocket().setKeepAlive(requireTCPKeepAlive);

        // Construct and send a startup packet.
        sendStartupPacket(newStream, user, database, logger);

        // Do authentication (until AuthenticationOk).
        doAuthentication(newStream, user, PGProperty.PASSWORD.get(info), logger);

        // Do final startup.
        ProtocolConnectionImpl protoConnection =
            new ProtocolConnectionImpl(newStream, user, database, logger, connectTimeout);
        readStartupMessages(newStream, protoConnection, logger);

        // Check Master or Slave
        HostStatus hostStatus = HostStatus.ConnectOK;
        if (targetServerType != HostRequirement.any) {
          hostStatus = isMaster(protoConnection, logger) ? HostStatus.Master : HostStatus.Slave;
        }
        GlobalHostStatusTracker.reportHostStatus(hostSpec, hostStatus);
        if (!targetServerType.allowConnectingTo(hostStatus)) {
          protoConnection.close();
          if (hostIter.hasNext()) {
            // still more addresses to try
            continue;
          }
          throw new PSQLException(GT
              .tr("Could not find a server with specified targetServerType: {0}", targetServerType),
              PSQLState.CONNECTION_UNABLE_TO_CONNECT);
        }

        // Run some initial queries
        runInitialQueries(protoConnection, info, logger);

        // And we're done.
        return protoConnection;
      } catch (ConnectException cex) {
        // Added by Peter Mount <peter@retep.org.uk>
        // ConnectException is thrown when the connection cannot be made.
        // we trap this an return a more meaningful message for the end user
        GlobalHostStatusTracker.reportHostStatus(hostSpec, HostStatus.ConnectFail);
        if (hostIter.hasNext()) {
          // still more addresses to try
          continue;
        }
        throw new PSQLException(
            GT.tr(
                "Connection refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections."),
            PSQLState.CONNECTION_UNABLE_TO_CONNECT, cex);
      } catch (IOException ioe) {
        closeStream(newStream);
        GlobalHostStatusTracker.reportHostStatus(hostSpec, HostStatus.ConnectFail);
        if (hostIter.hasNext()) {
          // still more addresses to try
          continue;
        }
        throw new PSQLException(GT.tr("The connection attempt failed."),
            PSQLState.CONNECTION_UNABLE_TO_CONNECT, ioe);
      } catch (SQLException se) {
        closeStream(newStream);
        if (hostIter.hasNext()) {
          // still more addresses to try
          continue;
        }
        throw se;
      }
    }
    throw new PSQLException(GT.tr("The connection url is invalid."),
        PSQLState.CONNECTION_UNABLE_TO_CONNECT);
  }

  private PGStream enableSSL(PGStream pgStream, boolean requireSSL, Properties info, Logger logger,
      int connectTimeout) throws IOException, SQLException {
    if (logger.logDebug()) {
      logger.debug(" FE=> SSLRequest");
    }

    // Send SSL request packet
    pgStream.SendInteger4(8);
    pgStream.SendInteger2(1234);
    pgStream.SendInteger2(5679);
    pgStream.flush();

    // Now get the response from the backend, one of N, E, S.
    int beresp = pgStream.ReceiveChar();
    switch (beresp) {
      case 'E':
        if (logger.logDebug()) {
          logger.debug(" <=BE SSLError");
        }

        // Server doesn't even know about the SSL handshake protocol
        if (requireSSL) {
          throw new PSQLException(GT.tr("The server does not support SSL."),
              PSQLState.CONNECTION_REJECTED);
        }

        // We have to reconnect to continue.
        pgStream.close();
        return new PGStream(pgStream.getSocketFactory(), pgStream.getHostSpec(), connectTimeout);

      case 'N':
        if (logger.logDebug()) {
          logger.debug(" <=BE SSLRefused");
        }

        // Server does not support ssl
        if (requireSSL) {
          throw new PSQLException(GT.tr("The server does not support SSL."),
              PSQLState.CONNECTION_REJECTED);
        }

        return pgStream;

      case 'S':
        if (logger.logDebug()) {
          logger.debug(" <=BE SSLOk");
        }

        // Server supports ssl
        org.postgresql.ssl.MakeSSL.convert(pgStream, info, logger);
        return pgStream;

      default:
        throw new PSQLException(GT.tr("An error occurred while setting up the SSL connection."),
            PSQLState.PROTOCOL_VIOLATION);
    }
  }

  private void sendStartupPacket(PGStream pgStream, String user, String database, Logger logger)
      throws IOException {
    // 4: total size including self
    // 2: protocol major
    // 2: protocol minor
    // 64: database name
    // 32: user name
    // 64: options
    // 64: unused
    // 64: tty

    if (logger.logDebug()) {
      logger.debug(" FE=> StartupPacket(user=" + user + ",database=" + database + ")");
    }

    pgStream.SendInteger4(4 + 4 + 64 + 32 + 64 + 64 + 64);
    pgStream.SendInteger2(2); // protocol major
    pgStream.SendInteger2(0); // protocol minor
    pgStream.Send(database.getBytes("UTF-8"), 64);
    pgStream.Send(user.getBytes("UTF-8"), 32);
    pgStream.Send(new byte[64]); // options
    pgStream.Send(new byte[64]); // unused
    pgStream.Send(new byte[64]); // tty
    pgStream.flush();
  }

  private void doAuthentication(PGStream pgStream, String user, String password, Logger logger)
      throws IOException, SQLException {
    // Now get the response from the backend, either an error message
    // or an authentication request

    while (true) {
      int beresp = pgStream.ReceiveChar();

      switch (beresp) {
        case 'E':
          // An error occurred, so pass the error message to the
          // user.
          //
          // The most common one to be thrown here is:
          // "User authentication failed"
          //
          String errorMsg = pgStream.ReceiveString();
          if (logger.logDebug()) {
            logger.debug(" <=BE ErrorMessage(" + errorMsg + ")");
          }
          throw new PSQLException(GT.tr("Connection rejected: {0}.", errorMsg),
              PSQLState.CONNECTION_REJECTED);

        case 'R':
          // Authentication request.
          // Get the type of request
          int areq = pgStream.ReceiveInteger4();

          // Process the request.
          switch (areq) {
            case AUTH_REQ_CRYPT: {
              byte salt[] = pgStream.Receive(2);

              if (logger.logDebug()) {
                logger.debug(
                    " <=BE AuthenticationReqCrypt(salt='" + new String(salt, "US-ASCII") + "')");
              }

              if (password == null) {
                throw new PSQLException(
                    GT.tr(
                        "The server requested password-based authentication, but no password was provided."),
                    PSQLState.CONNECTION_REJECTED);
              }

              byte[] encodedResult = UnixCrypt.crypt(salt, password.getBytes("UTF-8"));

              if (logger.logDebug()) {
                logger
                    .debug(" FE=> Password(crypt='" + new String(encodedResult, "US-ASCII") + "')");
              }

              pgStream.SendInteger4(4 + encodedResult.length + 1);
              pgStream.Send(encodedResult);
              pgStream.SendChar(0);
              pgStream.flush();

              break;
            }

            case AUTH_REQ_MD5: {
              byte[] md5Salt = pgStream.Receive(4);
              if (logger.logDebug()) {
                logger.debug(" <=BE AuthenticationReqMD5(salt=" + Utils.toHexString(md5Salt) + ")");
              }

              if (password == null) {
                throw new PSQLException(
                    GT.tr(
                        "The server requested password-based authentication, but no password was provided."),
                    PSQLState.CONNECTION_REJECTED);
              }

              byte[] digest =
                  MD5Digest.encode(user.getBytes("UTF-8"), password.getBytes("UTF-8"), md5Salt);
              if (logger.logDebug()) {
                logger.debug(" FE=> Password(md5digest=" + new String(digest, "US-ASCII") + ")");
              }

              pgStream.SendInteger4(4 + digest.length + 1);
              pgStream.Send(digest);
              pgStream.SendChar(0);
              pgStream.flush();

              break;
            }

            case AUTH_REQ_PASSWORD: {
              if (logger.logDebug()) {
                logger.debug(" <=BE AuthenticationReqPassword");
              }

              if (password == null) {
                throw new PSQLException(
                    GT.tr(
                        "The server requested password-based authentication, but no password was provided."),
                    PSQLState.CONNECTION_REJECTED);
              }

              if (logger.logDebug()) {
                logger.debug(" FE=> Password(password=<not shown>)");
              }

              byte[] encodedPassword = password.getBytes("UTF-8");
              pgStream.SendInteger4(4 + encodedPassword.length + 1);
              pgStream.Send(encodedPassword);
              pgStream.SendChar(0);
              pgStream.flush();

              break;
            }

            case AUTH_REQ_OK:
              if (logger.logDebug()) {
                logger.debug(" <=BE AuthenticationOk");
              }

              return; // We're done.

            default:
              if (logger.logDebug()) {
                logger.debug(" <=BE AuthenticationReq (unsupported type " + (areq) + ")");
              }

              throw new PSQLException(GT.tr(
                  "The authentication type {0} is not supported. Check that you have configured the pg_hba.conf file to include the client''s IP address or subnet, and that it is using an authentication scheme supported by the driver.",
                  areq), PSQLState.CONNECTION_REJECTED);
          }

          break;

        default:
          throw new PSQLException(GT.tr("Protocol error.  Session setup failed."),
              PSQLState.PROTOCOL_VIOLATION);
      }
    }
  }

  private void readStartupMessages(PGStream pgStream, ProtocolConnectionImpl protoConnection,
      Logger logger) throws IOException, SQLException {
    while (true) {
      int beresp = pgStream.ReceiveChar();
      switch (beresp) {
        case 'Z': // ReadyForQuery
          if (logger.logDebug()) {
            logger.debug(" <=BE ReadyForQuery");
          }
          return;

        case 'K': // BackendKeyData
          int pid = pgStream.ReceiveInteger4();
          int ckey = pgStream.ReceiveInteger4();
          if (logger.logDebug()) {
            logger.debug(" <=BE BackendKeyData(pid=" + pid + ",ckey=" + ckey + ")");
          }
          protoConnection.setBackendKeyData(pid, ckey);
          break;

        case 'E': // ErrorResponse
          String errorMsg = pgStream.ReceiveString();
          if (logger.logDebug()) {
            logger.debug(" <=BE ErrorResponse(" + errorMsg + ")");
          }
          throw new PSQLException(GT.tr("Backend start-up failed: {0}.", errorMsg),
              PSQLState.CONNECTION_UNABLE_TO_CONNECT);

        case 'N': // NoticeResponse
          String warnMsg = pgStream.ReceiveString();
          if (logger.logDebug()) {
            logger.debug(" <=BE NoticeResponse(" + warnMsg + ")");
          }
          protoConnection.addWarning(new SQLWarning(warnMsg));
          break;

        default:
          throw new PSQLException(GT.tr("Protocol error.  Session setup failed."),
              PSQLState.PROTOCOL_VIOLATION);
      }
    }
  }

  private void runInitialQueries(ProtocolConnectionImpl protoConnection, Properties info,
      Logger logger) throws SQLException, IOException {
    byte[][] results = SetupQueryRunner.run(protoConnection,
        "set datestyle = 'ISO'; select version(), case when pg_encoding_to_char(1) = 'SQL_ASCII' then 'UNKNOWN' else getdatabaseencoding() end",
        true);

    String rawDbVersion = protoConnection.getEncoding().decode(results[0]);
    StringTokenizer versionParts = new StringTokenizer(rawDbVersion);
    versionParts.nextToken(); /* "PostgreSQL" */
    String dbVersion = versionParts.nextToken(); /* "X.Y.Z" */

    protoConnection.setServerVersion(dbVersion);

    if (dbVersion.compareTo("7.3") >= 0) {
      // set encoding to be unicode; set datestyle; ensure autocommit is on
      // (no-op on 7.4, but might be needed under 7.3)
      // The begin/commit is to avoid leaving a transaction open if we're talking to a
      // 7.3 server that defaults to autocommit = off.

      if (logger.logDebug()) {
        logger.debug("Switching to UTF8 client_encoding");
      }

      String sql = "begin; ";

      // server configuration parameter autocommit was deprecated
      // and non-operational on version 9.5
      if (dbVersion.compareTo("9.5") < 0) {
        sql += "set autocommit = on; ";
      }

      sql += "set client_encoding = 'UTF8'; ";

      if (dbVersion.compareTo("9.0") >= 0) {
        sql += "SET extra_float_digits=3; ";
      } else if (dbVersion.compareTo("7.4") >= 0) {
        sql += "SET extra_float_digits=2; ";
      }
      sql += "commit";

      SetupQueryRunner.run(protoConnection, sql, false);
      protoConnection.setEncoding(Encoding.getDatabaseEncoding("UTF8"));
    } else {
      String charSet = PGProperty.CHARSET.get(info);
      String dbEncoding =
          (results[1] == null ? null : protoConnection.getEncoding().decode(results[1]));
      if (logger.logDebug()) {
        logger.debug("Specified charset:  " + charSet);
        logger.debug("Database encoding: " + dbEncoding);
      }

      if (charSet != null) {
        // Explicitly specified encoding.
        protoConnection.setEncoding(Encoding.getJVMEncoding(charSet));
      } else if (dbEncoding != null) {
        // Use database-supplied encoding.
        protoConnection.setEncoding(Encoding.getDatabaseEncoding(dbEncoding));
      } else {
        // Fall back to defaults.
        // XXX is this ever reached?
        protoConnection.setEncoding(Encoding.defaultEncoding());
      }
    }

    if (logger.logDebug()) {
      logger.debug(
          "Connection encoding (using JVM's nomenclature): " + protoConnection.getEncoding());
    }

    if (dbVersion.compareTo("8.1") >= 0) {
      // Server versions since 8.1 report standard_conforming_strings
      results = SetupQueryRunner.run(protoConnection,
          "select current_setting('standard_conforming_strings')", true);
      String value = protoConnection.getEncoding().decode(results[0]);
      protoConnection.setStandardConformingStrings(value.equalsIgnoreCase("on"));
    } else {
      protoConnection.setStandardConformingStrings(false);
    }

    String appName = PGProperty.APPLICATION_NAME.get(info);
    if (appName != null && dbVersion.compareTo("9.0") >= 0) {
      StringBuilder sb = new StringBuilder("SET application_name = '");
      Utils.escapeLiteral(sb, appName, protoConnection.getStandardConformingStrings());
      sb.append("'");
      SetupQueryRunner.run(protoConnection, sb.toString(), false);
    }

    String currentSchema = PGProperty.CURRENT_SCHEMA.get(info);
    if (currentSchema != null) {
      StringBuilder sb = new StringBuilder("SET search_path = '");
      Utils.escapeLiteral(sb, appName, protoConnection.getStandardConformingStrings());
      sb.append("'");
      SetupQueryRunner.run(protoConnection, sb.toString(), false);
    }
  }

  private boolean isMaster(ProtocolConnectionImpl protoConnection, Logger logger)
      throws SQLException, IOException {
    byte[][] results = SetupQueryRunner.run(protoConnection, "show transaction_read_only", true);
    String value = protoConnection.getEncoding().decode(results[0]);
    return value.equalsIgnoreCase("off");
  }
}
