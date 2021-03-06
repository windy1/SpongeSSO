package db

import java.io.File
import java.nio.file.Files._
import java.nio.file._
import java.sql.Timestamp
import java.util.{Date, UUID}
import javax.inject.Inject

import com.google.common.base.Preconditions._
import controllers.routes
import db.schema.user.{DeletedUserTable, UserTable}
import db.schema.{EmailConfirmationTable, OneTimePasswordTable, PasswordResetTable, SessionTable}
import external.LetterAvatars
import form.{SettingsForm, SignUpForm, TSignUpForm}
import models.{Session => _, _}
import org.spongepowered.play.security.CryptoUtils._
import play.api.db.slick.DatabaseConfigProvider
import play.api.mvc.{Cookie, Request}
import security.SpongeAuthConfig
import security.pwd.PasswordFactory
import security.totp.TotpAuth
import slick.backend.DatabaseConfig
import slick.driver.JdbcProfile
import slick.driver.PostgresDriver.api._

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.languageFeature.implicitConversions

/**
  * Contains all [[User]] information.
  */
trait UserDAO {

  val dbConfig: DatabaseConfig[JdbcProfile]
  val users = TableQuery[UserTable]
  val deletedUsers = TableQuery[DeletedUserTable]
  val sessions = TableQuery[SessionTable]
  val emailConfirms = TableQuery[EmailConfirmationTable]
  val passwordResets = TableQuery[PasswordResetTable]
  val otps = TableQuery[OneTimePasswordTable]

  /** The maximum wait time for database queries */
  val timeout: Duration = 10.seconds
  /** The maximum age of a [[models.Session]] */
  val maxSessionAge: Int
  /** The maximum age of an [[EmailConfirmation]] */
  val maxEmailConfirmationAge: Long
  /** The maximum age of an [[PasswordReset]] */
  val maxPasswordResetAge: Long
  /** [[TotpAuth]] instance */
  val totp: TotpAuth
  /** Secret string used for two-way encryption */
  val encryptionSecret: String
  /** PasswordFactory instance */
  val passwords: PasswordFactory
  /** The base uploads directory */
  val uploadsDir: Path
  val letterAvatars: LetterAvatars

  implicit val self = this

  import dbConfig.db
  import models.{Session => DbSession}

  private def await[R](future: Future[R]): R = Await.result(future, this.timeout)

  protected def theTime: Timestamp = new Timestamp(new Date().getTime)

  /**
    * Creates a new [[User]] using the specified [[SignUpForm]].
    *
    * @param formData Form data to process
    * @return         New user
    */
  def createUser(formData: TSignUpForm,
                 avatarUrl: String,
                 verified: Boolean = false,
                 dummy: Boolean = false): User = {
    checkNotNull(formData, "null form data", "")

    // Create user
    val google = formData.googleSubject.isDefined
    val pwd = if (dummy || google) None else Some(this.passwords.hash(formData.password.get))
    var user = new User(formData, theTime, avatarUrl, pwd).copy(isEmailConfirmed = verified || google)
    val insertion = this.users returning this.users += user
    await(db run insertion)
  }

  private def saveSetting(user: User, rep: UserTable => Rep[String], value: Option[String]) = {
    val stmt = for { u <- this.users if u.id === user.id.get } yield rep(u)
    await(db run stmt.update(value.orNull))
  }

  /**
    * Saves the specified settings to the specified [[User]]'s account.
    *
    * @param user     User to save
    * @param formData Settings to save
    * @return         Updated user
    */
  def saveSettings(user: User, formData: SettingsForm): User = {
    checkNotNull(user, "null user", "")
    checkArgument(user.id.isDefined, "undefined user", "")
    checkNotNull(formData, "null form data", "")
    saveSetting(user, _.mcUsername, formData.mcUsername)
    saveSetting(user, _.ghUsername, formData.ghUsername)
    saveSetting(user, _.ircNick, formData.ircNick)
    get(user.id.get).get
  }

  private def getAvatarDir(user: User) = this.uploadsDir.resolve(s"users/${user.username}/avatar")

  /**
    * Sets the specified user's avatar to the specified file with the given
    * name.
    *
    * @param user User to set avatar of
    * @param name File name
    * @param file Avatar file
    * @return     Updated user
    */
  def setAvatar(user: User, name: String, file: File): User = {
    checkNotNull(user, "null user", "")
    checkArgument(user.id.isDefined, "undefined user", "")
    checkNotNull(name, "null filename", "")
    checkNotNull(file, "null file", "")
    val path = getAvatarDir(user).resolve(name)
    if (notExists(path.getParent))
      createDirectories(path.getParent)
    list(path.getParent).iterator().asScala.foreach(delete)
    copy(file.toPath, path)
    saveSetting(user, _.avatarUrl, Some(routes.Settings.showAvatar(user.username).path()))
    get(user.id.get).get
  }

  /**
    * Sets the specified user's avatar to the specified URL.
    *
    * @param user User to set avatar of
    * @param url  Avatar URL
    * @return     Updated user
    */
  def setAvatar(user: User, url: String): User = {
    checkNotNull(user, "null user", "")
    checkArgument(user.id.isDefined, "undefined user", "")
    saveSetting(user, _.avatarUrl, Option(url))
    get(user.id.get).get
  }

  /**
    * Resets this user's avatar to the system default.
    *
    * @param user User to reset avatar of
    * @return     Updated user
    */
  def resetAvatar(user: User): User = setAvatar(user, this.letterAvatars.getUrl(user.username))

  /**
    * Returns path to the specified user's avatar.
    *
    * @param user User to get avatar path of
    * @return     Path to user's avatar
    */
  def getAvatarPath(user: User): Option[Path] = {
    checkNotNull(user, "null user", "")
    checkArgument(user.id.isDefined, "undefined user", "")
    val javaOpt = list(getAvatarDir(user)).findFirst()
    if (javaOpt.isPresent)
      Some(javaOpt.get())
    else
      None
  }

  /**
    * Inserts the specified [[User]] into the deleted users table and deletes
    * it from the regular user table.
    *
    * @param user User to delete
    * @return     Deleted user
    */
  def deleteUser(user: User): User = {
    checkNotNull(user, "null user", "")
    checkArgument(user.id.isDefined, "undefined user", "")
    var deletedUser = user.copy(deletedAt = Some(theTime))
    val insertion = this.deletedUsers returning this.deletedUsers += deletedUser
    deletedUser = await(db run insertion)

    await(db run this.sessions.filter(_.username === user.username).delete)
    await(db run this.emailConfirms.filter(_.email === user.email).delete)
    await(db run this.passwordResets.filter(_.email === user.email).delete)

    val deletion = this.users.filter(_.id === user.id.get).delete
    await(db run deletion)
    deletedUser
  }

  /**
    * Deletes the pending [[EmailConfirmation]] of the specified token if it
    * exists and marks the user as having their email confirmed.
    *
    * @param token  Token to lookup
    * @return       New session if email confirmed
    */
  def confirmEmail(token: String) = {
    checkNotNull(token, "null token", "")
    getTokenExpirable[EmailConfirmation](this.emailConfirms, token).map { confirmation =>
      db.run(this.emailConfirms.filter(_.token === token).delete)
      val updateUserQuery = for { u <- this.users if u.email === confirmation.email } yield u.isEmailConfirmed
      await(db.run(updateUserQuery.update(true)))
    }
  }

  /**
    * Returns the [[EmailConfirmation]] associated with the specified email,
    * if any. The [[EmailConfirmation]] is deleted immediately if it has
    * expired and None will be returned.
    *
    * @param email  Email to lookup
    * @return       EmailConfirmation if found
    */
  def getEmailConfirmation(email: String): Option[EmailConfirmation] = {
    checkNotNull(email, "null email", "")
    await(db.run(this.emailConfirms.filter(_.email === email).result).map(_.headOption)).flatMap { confirmation =>
      if (confirmation.hasExpired) {
        await(db.run(this.emailConfirms.filter(_.id === confirmation.id).delete))
        None
      } else
        Some(confirmation)
    }
  }

  /**
    * Creates a new [[EmailConfirmation]] for the specified [[User]].
    *
    * @param user User to create confirmation for
    * @return     New EmailConfirmation
    */
  def createEmailConfirmation(user: User): EmailConfirmation = {
    checkNotNull(user, "null user", "")
    checkArgument(user.id.isDefined, "undefined user", "")
    val emailToken = UUID.randomUUID().toString.replace("-", "")
    val confirmExpiration = new Timestamp(new Date().getTime + this.maxEmailConfirmationAge)
    val emailConfirm = EmailConfirmation(None, theTime, confirmExpiration, user.email, emailToken)
    val emailConfirmInsert = this.emailConfirms returning this.emailConfirms += emailConfirm
    await(db.run(emailConfirmInsert))
  }

  /**
    * Deletes any [[EmailConfirmation]]s for the specified email.
    *
    * @param email Email to delete confirmations for
    */
  def deleteEmailConfirmation(email: String) = {
    checkNotNull(email, "null email", "")
    await(db.run(this.emailConfirms.filter(_.email === email).delete))
  }

  /**
    * Creates a new [[models.Session]] for the specified [[User]].
    *
    * @param user User to create session for
    * @return     New session
    */
  def createSession(user: User, authenticated: Boolean = false): DbSession = {
    checkNotNull(user, "null user", "")
    checkArgument(user.id.isDefined, "undefined user", "")
    val token = UUID.randomUUID().toString
    val expiration = new Timestamp(new Date().getTime + maxSessionAge * 1000L)
    var session = DbSession(
      createdAt = theTime,
      expiration = expiration,
      username = user.username,
      token = token,
      isAuthenticated = authenticated)
    val sessionInsert = this.sessions returning this.sessions += session
    await(db.run(sessionInsert))
  }

  /**
    * Marks the specified session as authenticated.
    *
    * @param session Session to mark as authenticated
    */
  def setSessionAuthenticated(session: DbSession) = {
    checkNotNull(session, "null session", "")
    checkArgument(session.id.isDefined, "undefined session", "")
    val query = for { s <- this.sessions if s.id === session.id.get } yield s.isAuthenticated
    await(db.run(query.update(true)))
  }

  /**
    * Marks the user as having confirmed their given TOTP secret.
    *
    * @param user User to set TOTP confirmed status of
    */
  def setTotpConfirmed(user: User, confirmed: Boolean = true) = {
    checkNotNull(user, "null user", "")
    checkArgument(user.id.isDefined, "undefined user", "")
    val query = for { u <- this.users if u.id === user.id.get } yield u.isTotpConfirmed
    await(db.run(query.update(confirmed)))
  }

  /**
    * Creates a new Cookie to be given to the client to identify their session.
    *
    * @param session  Session to create cookie for
    * @return         Session cookie
    */
  def createSessionCookie(session: DbSession) = {
    checkNotNull(session, "null session", "")
    checkArgument(session.id.isDefined, "undefined session", "")
    Cookie("_token", session.token, Some(this.maxSessionAge))
  }

  /**
    * Enables and generates a new secret for the specified [[User]]. If TOTP is
    * already enabled for this User an exception will be thrown.
    *
    * @param user User to enable TOTP for
    * @return     Updated User
    */
  def generateTotpSecret(user: User): User = {
    checkNotNull(user, "null user", "")
    checkArgument(user.id.isDefined, "undefined user", "")
    if (user.isTotpConfirmed)
      throw new Exception("user already has TOTP enabled")
    val query = for { u <- this.users if u.id === user.id.get } yield u.totpSecret
    val secret = encrypt(this.totp.generateSecret(), this.encryptionSecret)
    await(db.run(query.update(secret)))
    get(user.id.get).get
  }

  /**
    * Verifies that the specified TOTP is valid for the specified [[User]] at
    * the current time.
    *
    * @param user User to check for
    * @param code TOTP code to check
    * @return     True if verified
    */
  def verifyTotp(user: User, code: Int): Boolean = {
    checkNotNull(user, "null user", "")
    checkArgument(user.id.isDefined, "undefined user", "")
    checkArgument(user.totpSecret.isDefined, "totp disabled for user", "")
    val userId = user.id.get
    if (await(db.run(this.otps.filter(otp => otp.userId === userId && otp.code === code).result)).nonEmpty)
      return false
    val secret = decrypt(user.totpSecret.get, this.encryptionSecret)
    val result = this.totp.checkCode(secret, code)
    if (result)
      await(db.run(this.otps += OneTimePassword(createdAt = theTime, userId = userId, code = code)))
    result
  }

  /**
    * Increments the specified [[User]]'s failedTotpAttempts field by one.
    *
    * @param user User to update
    * @return     Updated user
    */
  def addFailedTotpAttempt(user: User): User = {
    checkNotNull(user, "null user", "")
    checkArgument(user.id.isDefined, "undefined user", "")
    val query = for { u <- this.users if u.id === user.id.get } yield u.failedTotpAttempts
    await(db.run(query.update(user.failedTotpAttempts + 1)))
    get(user.id.get).get
  }

  /**
    * Returns the currently authenticated [[User]], if any.
    *
    * @param request  Request of user
    * @return         Authenticated User if found, None otherwise
    */
  def current(implicit request: Request[_]): Option[User] = {
    checkNotNull(request, "null request", "")
    getSession.flatMap { session =>
      if (session.isAuthenticated)
        withName(session.username)
      else
        None
    }
  }

  /**
    * Retrieves the session for the specified token if it exists and has not
    * expired. If expired, the session will be deleted immediately and None
    * will be returned.
    *
    * @param token  Session token
    * @return       Session if found and has not expired
    */
  def getSession(token: String): Option[DbSession] = getTokenExpirable[DbSession](this.sessions, token)

  /**
    * Retrieves the session for the specified request if it exists and has not
    * expired. If expired, the session will be deleted immediately and None
    * will be returned.
    *
    * @param request  Incoming request
    * @return         Session of request
    */
  def getSession(implicit request: Request[_]): Option[DbSession] = {
    checkNotNull(request, "null request", "")
    request.cookies.get("_token").flatMap(token => getSession(token.value))
  }

  private def getTokenExpirable[M <: TokenExpirable](query: TableQuery[M#T], token: String): Option[M] = {
    checkNotNull(query, "null query", "")
    checkNotNull(token, "null token", "")
    await(db.run(query.filter(_.token === token).result).map(_.headOption)).flatMap { model =>
      if (model.hasExpired) {
        await(db.run(query.filter(_.id === model.id).delete))
        None
      } else
        Some(model)
    }.map(_.asInstanceOf[M])
  }

  /**
    * Deletes the session with the specified token if any.
    *
    * @param token Token of session
    */
  def deleteSession(token: String) = {
    checkNotNull(token, "null token", "")
    await(db.run(this.sessions.filter(_.token === token).delete))
  }

  /**
    * Verifies the specified username's password and returns the user if
    * successful.
    *
    * @param username Username to check password of
    * @param password Plain text password to check
    * @return         User if successful, None otherwise
    */
  def verify(username: String, password: String): Option[User] = withName(username).flatMap { user =>
    checkNotNull(username, "null username", "")
    checkNotNull(password, "null password", "")
    if (user.password.isDefined && this.passwords.check(password, user.password.get, user.salt.get))
      Some(user)
    else
      None
  }

  /**
    * Deletes any [[PasswordReset]]s associated with the specified [[User]].
    *
    * @param user User to delete password resets of
    */
  def deletePasswordReset(user: User) = {
    checkNotNull(user, "null user", "")
    checkArgument(user.id.isDefined, "undefined user", "")
    await(db.run(this.passwordResets.filter(_.email === user.email).delete))
  }

  /**
    * Creates a new instance of a [[PasswordReset]] for the specified [[User]].
    *
    * @param user User that is resetting their password
    * @return     New PasswordReset instance
    */
  def createPasswordReset(user: User): PasswordReset = {
    checkNotNull(user, "null user", "")
    checkArgument(user.id.isDefined, "undefined user", "")
    val expiration = new Timestamp(new Date().getTime + this.maxPasswordResetAge)
    val token = UUID.randomUUID().toString.replace("-", "")
    val model = PasswordReset(None, theTime, expiration, token, user.email)
    val query = this.passwordResets returning this.passwordResets += model
    await(db.run(query))
  }

  /**
    * Returns a [[PasswordReset]] model with the given token. If the model has
    * expired it will be deleted immediately and None will be returned.
    *
    * @param token  Token to lookup
    * @return       PasswordReset with token
    */
  def getPasswordReset(token: String): Option[PasswordReset]
  = getTokenExpirable[PasswordReset](this.passwordResets, token)

  /**
    * Resets a user's password using a unique [[PasswordReset]] token to
    * verify the request. The associated PasswordReset will be deleted once the
    * user's password has been updated.
    *
    * @param token        Token to lookup
    * @param newPassword  New password to set
    * @return             True if successful
    */
  def resetPassword(token: String, newPassword: String): Boolean = {
    checkNotNull(newPassword, "null password", "")
    getPasswordReset(token).exists { model =>
      val user = model.user
      val updateStatement = for { u <- this.users if u.id === user.id.get } yield (u.password, u.salt)
      val pwd = this.passwords.hash(newPassword)
      await(db.run(updateStatement.update(pwd.hash, pwd.salt)))
      deletePasswordReset(user)
      true
    }
  }

  /**
    * Removes all [[User]]s from the database.
    */
  def removeAll() = {
    val users = await(db run this.users.result)
    for (user <- users)
      deleteUser(user)
    await(db run this.deletedUsers.delete)
  }

  /**
    * Returns the [[User]] with the specified ID, if any.
    *
    * @param id ID to get
    * @return   User if found, None otherwise
    */
  def get(id: Int): Option[User] = await(db.run(this.users.filter(_.id === id).result).map(_.headOption))

  /**
    * Returns the [[User]] with the specified username if any.
    *
    * @param username Username to lookup
    * @return         User if found, None otherwise
    */
  def withName(username: String): Option[User] = {
    checkNotNull(username, "null username", "")
    await(db.run(this.users.filter(_.username.toLowerCase === username.toLowerCase).result).map(_.headOption))
  }

  /**
    * Returns the [[User]] with the specified email, if any.
    *
    * @param email  Email to lookup
    * @return       User if found, None otherwise
    */
  def withEmail(email: String): Option[User] = {
    checkNotNull(email, "null email", "")
    await(db.run(this.users.filter(_.email === email).result).map(_.headOption))
  }

  /**
    * Returns the [[User]] with the specified Google ID if any.
    *
    * @param googleId Google ID to lookup
    * @return         User if found, None otherwise
    */
  def withGoogleId(googleId: String): Option[User] = {
    checkNotNull(googleId, "null google id", "")
    await(db.run(this.users.filter(_.googleId === googleId).result).map(_.headOption))
  }

  /**
    * Checks if the specified user field is unique for the specified value.
    *
    * @param rep    User field
    * @param value  Value to check
    * @return       True if unique
    */
  def isFieldUnique(rep: UserTable => Rep[String], value: String, excluding: User = null): Boolean = {
    checkNotNull(rep, "null rep", "")
    var filter = this.users.filter(rep(_) === value)
    if (excluding != null)
      filter = filter.filterNot(_.id === excluding.id.get)
    val query = !filter.exists
    await(db run query.result)
  }

  def migrate() = {
    db.run(this.users.filter(_.avatarUrl === "/assets/images/spongie.png").result).andThen {
      case result =>
        for (user <- result.get) {
          val statement = for { u <- this.users if u.id === user.id.get } yield u.avatarUrl
          db.run(statement.update(this.letterAvatars.getUrl(user.username)))
        }
    }
  }

}

final class UserDAOImpl @Inject()(provider: DatabaseConfigProvider,
                                  config: SpongeAuthConfig,
                                  override val letterAvatars: LetterAvatars,
                                  override val passwords: PasswordFactory,
                                  override val totp: TotpAuth) extends UserDAO {

  override val dbConfig = this.provider.get[JdbcProfile]
  override val timeout = this.config.db.getLong("timeout").get.millis
  override val maxSessionAge = this.config.play.getInt("http.session.maxAge").get
  override val maxEmailConfirmationAge = this.config.mail.getLong("confirm.maxAge").get
  override val maxPasswordResetAge = this.config.security.getLong("password.maxResetAge").get
  override val encryptionSecret = this.config.play.getString("crypto.secret").get
  override val uploadsDir = Paths.get(this.config.app.getString("uploadsDir").get)

}
