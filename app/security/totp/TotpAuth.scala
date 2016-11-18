package security.totp

import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Date
import javax.inject.Inject

import com.google.common.base.Preconditions._
import org.apache.commons.codec.binary.Base32
import org.spongepowered.play.security.CryptoUtils._
import security.SpongeAuthConfig

import scala.concurrent.duration._

/**
  * Handles generation and validation of TOTP token as specified by
  * [[https://tools.ietf.org/html/rfc6238 RFC6238]]
  *
  *   As defined in [[https://tools.ietf.org/html/rfc4226 RFC4226]], the HOTP
  *   algorithm is based on the HMAC-SHA-1 algorithm (as specified in
  *   [[https://www.ietf.org/rfc/rfc2104.txt RFC2104]]) and applied to an
  *   increasing counter value representing the message in the HMAC
  *   computation.
  *
  *   Basically, the output of the HMAC-SHA-1 calculation is truncated to
  *   obtain user-friendly values:
  *
  *     HOTP(K,C) = Truncate(HMAC-SHA-1(K,C))
  *
  *   where Truncate represents the function that can convert an HMAC-SHA-1
  *   value into an HOTP value.  K and C represent the shared secret and
  *   counter value; see [RFC4226] for detailed definitions.
  *
  *   TOTP is the time-based variant of this algorithm, where a value T,
  *   derived from a time reference and a time step, replaces the counter C in
  *   the HOTP computation.
  *
  *   TOTP implementations MAY use HMAC-SHA-256 or HMAC-SHA-512 functions,
  *   based on SHA-256 or SHA-512 [SHA2] hash functions, instead of the
  *   HMAC-SHA-1 function that has been specified for the HOTP computation in
  *   [RFC4226].
  *
  * Secret keys are generated using the [[SecureRandom]] (a cryptographically
  * strong RNG with non-deterministic output). The secret key is generated by
  * encoding a byte array filled by this RNG to Base32. This implementation
  * allows a period to be defined called the "window" where the implementation
  * will look backwards and forwards in time to allow for some wiggle room to
  * allow the user to enter their generated pin.
  */
trait TotpAuth {

  final val CharEncoding = "UTF-8"
  final val Random = new SecureRandom
  final val SecretCodec = new Base32

  /**
    * The algorithm that is used for hashing verification codes. This must be
    * agreed upon by both the server and client. Note that some implementations
    * do not allow other algorithms than the default one: HMAC-SHA-1.
    */
  val algo: String = HmacSha1

  /**
    * The interval in which tokens are invalidated. This must be agreed upon
    * by both the server and the prover client. For example, one might share
    * their secret with the client with an additional parameter that defines
    * this time step (aka "period").
    */
  val timeStep: Duration = 30.seconds

  /**
    * The amount of time we should look back and forward in time to give the
    * user some wiggle room. This is useful for network latency or out of sync
    * system clocks.
    */
  val window: Duration = 30.seconds

  /**
    * Amount of bytes in the randomly generated secrets.
    */
  val secretBytes: Int = 10

  /**
    * The amount of digits to truncate verification codes to.
    */
  val digits: Int = 6

  /**
    * The name of the party that issues the secret keys.
    */
  val issuer: String

  /**
    * Generates a new secret key.
    *
    * There MUST be a unique secret (key) for each prover. (RFC 6238 3.R1)
    *
    * The keys SHOULD be randomly generated or derived using key derivation
    * algorithms. (RFC 6238 3.R6)
    *
    * The keys MAY be stored in a tamper-resistant device and SHOULD be
    * protected against unauthorized access and usage. (RFC 6238 3.R7)
    *
    * @return New secret key
    */
  def generateSecret(): String = {
    val buffer: Array[Byte] = new Array(this.secretBytes)
    Random.nextBytes(buffer)
    SecretCodec.encodeAsString(buffer)
  }

  /**
    * Validates that the specified code is valid with the specified secret at
    * the specified timestamp (in seconds).
    *
    * @param secret Secret key
    * @param code   Validation code
    * @param time   The timestamp (in seconds) of which the code must be valid
    *               for (i.e. the code must have been generated within the last
    *               {{{timeStep +- window}}})
    * @return       True if valid code
    */
  def checkCode(secret: String, code: Int, time: Long): Boolean = {
    checkNotNull(secret, "null secret", "")
    checkArgument(secret.nonEmpty, "empty secret", "")
    checkArgument(code.toString.length == this.digits, "invalid amount of digits", "")
    checkArgument(time >= 0, "invalid timestamp", "")
    val decodedSecret = SecretCodec.decode(secret)
    // Check codes in the past and future to allow for some wiggle room
    val windowSecs = this.window.toSeconds
    for (window <- -windowSecs to windowSecs) {
      if (code == generateCode(decodedSecret, time + window))
        return true
    }
    false
  }

  /**
    * Validates that the specified code is valid with the specified secret at
    * the current time.
    *
    * @param secret Secret key
    * @param code   Validation code
    * @return       True if valid code
    */
  def checkCode(secret: String, code: Int): Boolean = checkCode(secret, code, currentTimeInterval)

  /**
    * Generates the code for the specified secret at the specified time.
    *
    * @param secret Secret key
    * @param time   Time interval in seconds
    * @return       Validation code
    */
  def generateCode(secret: Array[Byte], time: Long): Int = {
    checkNotNull(secret, "null secret", "")
    checkArgument(secret.nonEmpty, "empty secret", "")
    checkArgument(time >= 0, "invalid timestamp", "")

    // Generate hash from time
    val data = ByteBuffer.allocate(java.lang.Long.BYTES).putLong(time).array()
    val hash = hmac(this.algo, secret, data)

    // Truncate the hash to a 4 byte unsigned int using the last 4 bits as an
    // offset. Since Java has no unsigned int, we will use a long. The truncated
    // hash must also be converted to big-endian.
    // (aka "Dynamic Truncation")
    val offset = hash(hash.length - 1) & 0xf
    var truncatedHash: Long = 0
    for (i <- 0 until 4) {
      truncatedHash <<= 8
      truncatedHash |= (hash(offset + i) & 0xff)
    }

    // Truncate to smaller amount of digits
    truncatedHash &= 0x7FFFFFFF
    truncatedHash %= Math.pow(10, this.digits).toLong

    truncatedHash.toInt
  }

  /**
    * Generates the code for the specified secret at the current time.
    *
    * @param secret Secret key
    * @return       Validation code
    */
  def generateCode(secret: Array[Byte]): Int = generateCode(SecretCodec.decode(secret), currentTimeInterval)

  /**
    * Generates the code for the specified secret at the current time.
    *
    * @param secret Secret key
    * @return       Validation code
    */
  def generateCode(secret: String): Int = generateCode(SecretCodec.decode(secret))

  /**
    * Returns the current time interval for the algorithm.
    *
    * @return Current time interval
    */
  def currentTimeInterval: Long = new Date().getTime / this.timeStep.toMillis

  /**
    * Generates a new otpauth URI for the specified user and secret string. This
    * URI includes the issuer of the secret, the user it is intended for, the
    * hashing algorithm that is used, how many digits to truncate the code to,
    * and the time period in which tokens are valid. Note that some clients
    * may choose to ignore some of these parameters in favor of the default
    * specification. Changing the algorithm, digits, or period should be done
    * with caution.
    *
    * @param user   User string
    * @param secret User secret
    * @return       New URI string
    */
  def generateUri(user: String, secret: String): String = {
    checkNotNull(user, "null user", "")
    checkArgument(user.nonEmpty, "empty user", "")
    checkNotNull(secret, "null secret", "")
    checkArgument(secret.nonEmpty, "empty secret", "")
    s"otpauth://totp/$issuer:$user" +
      s"?secret=$secret" +
      s"&issuer=$issuer" +
      s"&algorithm=$algo" +
      s"&digits=$digits" +
      s"&period=$timeStep"
  }

}

final class TotpAuthImpl @Inject()(config: SpongeAuthConfig) extends TotpAuth {

  import config.totp.{getInt, getString}

  override val algo = getString("algo").get
  override val timeStep = getInt("timeStep").get.seconds
  override val window = getInt("window").get.seconds
  override val secretBytes = getInt("secretBytes").get
  override val digits = getInt("digits").get
  override val issuer = getString("issuer").get

}
