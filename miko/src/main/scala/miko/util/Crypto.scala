package miko.util
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.{KeyPairGenerator, Security}
import java.util.Date

import org.bouncycastle.bcpg.{ArmoredOutputStream, HashAlgorithmTags, PublicKeyAlgorithmTags, SymmetricKeyAlgorithmTags}
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.operator.jcajce.{JcaPGPContentSignerBuilder, JcaPGPDigestCalculatorProviderBuilder, JcaPGPKeyPair, JcePBESecretKeyEncryptorBuilder}
import org.bouncycastle.openpgp.{PGPKeyRingGenerator, PGPSignature}

case class PGPKeys(public: String, secret: String)
object Crypto {

  Security.addProvider(new BouncyCastleProvider)
  private val kpg = KeyPairGenerator.getInstance("RSA", "BC")
  kpg.initialize(2048)

  def main(args: Array[String]): Unit = {
    val keys = generateKeys("Foo", "Bar")

    println(keys.public)
    println(keys.secret)
  }

  def generateKeys(name: String, password: String): PGPKeys = {
    //Mostly taken from RSAKeyPairGenerator example

    val sha1Calc = new JcaPGPDigestCalculatorProviderBuilder().build.get(HashAlgorithmTags.SHA1)
    val masterKeyPair  = new JcaPGPKeyPair(PublicKeyAlgorithmTags.RSA_SIGN, kpg.genKeyPair(), new Date)

    val keyRingGenerator = new PGPKeyRingGenerator(
      PGPSignature.DEFAULT_CERTIFICATION,
      masterKeyPair,
      name,
      sha1Calc,
      null,
      null,
      new JcaPGPContentSignerBuilder(masterKeyPair.getPublicKey.getAlgorithm, HashAlgorithmTags.SHA1),
      new JcePBESecretKeyEncryptorBuilder(SymmetricKeyAlgorithmTags.CAST5, sha1Calc)
        .setProvider("BC")
        .build(password.toCharArray)
    )
    keyRingGenerator.addSubKey(new JcaPGPKeyPair(PublicKeyAlgorithmTags.RSA_ENCRYPT, kpg.genKeyPair(), new Date))

    val secretOut      = new ByteArrayOutputStream
    val secretArmorOut = new ArmoredOutputStream(secretOut)
    val publicOut      = new ByteArrayOutputStream
    val publicArmorOut = new ArmoredOutputStream(publicOut)

    keyRingGenerator.generateSecretKeyRing().encode(secretArmorOut)
    keyRingGenerator.generatePublicKeyRing().encode(publicArmorOut)

    secretArmorOut.close()
    publicArmorOut.close()

    PGPKeys(
      new String(publicOut.toByteArray, StandardCharsets.US_ASCII),
      new String(secretOut.toByteArray, StandardCharsets.US_ASCII)
    )
  }

}
