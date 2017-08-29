package com.avast.marathon.plugin.vault

import com.bettercloud.vault.{Vault, VaultConfig}
import mesosphere.marathon.plugin.plugin.PluginConfiguration
import mesosphere.marathon.plugin.task.RunSpecTaskProcessor
import mesosphere.marathon.plugin.{ApplicationSpec, EnvVarSecretRef, PodSpec, Secret}
import org.apache.mesos.Protos.Environment.Variable
import org.apache.mesos.Protos.ExecutorInfo.Builder
import org.apache.mesos.Protos.{TaskGroupInfo, TaskInfo}
import org.slf4j.LoggerFactory
import play.api.libs.json.{JsObject, _}

import scala.util.{Failure, Success, Try}

case class Configuration(address: String, token: String)

class VaultPlugin extends RunSpecTaskProcessor with PluginConfiguration {

  private val logger = LoggerFactory.getLogger(classOf[VaultPlugin])
  logger.info("Vault plugin instantiated")

  private var vault: Vault = _

  override def initialize(marathonInfo: Map[String, Any], configurationJson: JsObject): Unit = {
    val conf = configurationJson.as[Configuration](Json.reads[Configuration])
    assert(conf != null, "VaultPlugin not initialized with configuration info.")
    assert(conf.address != null, "Vault address not specified.")
    assert(conf.token != null, "Vault token not specified.")
    vault = new Vault(new VaultConfig().address(conf.address).token(conf.token).build())
    logger.info(s"VaultPlugin initialized with $conf")
  }

  /*
    {
      "env": {
        "abc": "def",
        "DB_PASSWORD": {
          "secret": "db_pwd"
        }
      },
      "secrets": {
        "db_pwd": {
          "source": "/path/to/vault/secret@password"
        }
      }
    }
  */

  def taskInfo(appSpec: ApplicationSpec, builder: TaskInfo.Builder): Unit = {
    val envBuilder = builder.getCommand.getEnvironment.toBuilder
    appSpec.env.foreach {
      case (name, v: EnvVarSecretRef) =>
        appSpec.secrets.get(v.secret) match {
          case Some(secret) =>
            getSecretValueFromVault(secret) match {
              case Success(secretValue) => envBuilder.addVariables(Variable.newBuilder().setName(name).setValue(secretValue))
              case Failure(e) => logger.error(s"Secret ${v.secret} in ${appSpec.id} application cannot be read from Vault (source: ${secret.source})", e)
            }
          case None => logger.error(s"Secret ${v.secret} for ${appSpec.id} application not found in secrets definition in Marathon")
        }
      case _ => // plain environment variable
    }

    envBuilder.addVariables(Variable.newBuilder()
      .setName("TESTVAR")
      .setValue("TESTVALUE"))

    val commandBuilder = builder.getCommand.toBuilder
    commandBuilder.setEnvironment(envBuilder)
    builder.setCommand(commandBuilder)
  }

  private def getSecretValueFromVault(secret: Secret): Try[String] = Try {
    val source = secret.source
    val indexOfAt = source.indexOf('@')
    val indexOfSplit = if (indexOfAt != -1) indexOfAt else source.lastIndexOf('/')
    if (indexOfSplit > 0) {
      val path = source.substring(0, indexOfSplit)
      val attribute = source.substring(indexOfSplit + 1)
      Option(vault.logical().read(path).getData.get(attribute)) match {
        case Some(secretValue) => Success(secretValue)
        case None => Failure(new RuntimeException(s"Secret $source obtained from Vault is empty"))
      }
    } else {
      Failure(new RuntimeException(s"Secret $source cannot be read because it cannot be parsed"))
    }
  }.flatten

  def taskGroup(podSpec: PodSpec, executor: Builder, taskGroup: TaskGroupInfo.Builder): Unit = {

  }
}
