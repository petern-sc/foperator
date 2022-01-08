package foperator.backend.kubernetesclient

import cats.Eq
import cats.effect.Async
import com.goyeau.kubernetes.client
import com.goyeau.kubernetes.client.KubernetesClient
import com.goyeau.kubernetes.client.crd.{CustomResource, CustomResourceList}
import foperator.Id
import foperator.types.HasSpec
import io.circe.{Decoder, Encoder}
import io.k8s.api.core.v1.{Pod, PodList, PodStatus}
import io.k8s.apiextensionsapiserver.pkg.apis.apiextensions.v1.{CustomResourceDefinition, CustomResourceDefinitionList, CustomResourceDefinitionStatus}
import io.k8s.apimachinery.pkg.apis.meta.v1.ObjectMeta
import org.http4s.Status

import scala.reflect.ClassTag

package object implicits extends foperator.CommonImplicits {
  import foperator.backend.KubernetesClient._

  // implicits that don't have a better place
  implicit val metadataEq: Eq[ObjectMeta] = Eq.fromUniversalEquals

  private def cantUpdateStatus[IO[_], T]
    (implicit io: Async[IO], ct: ClassTag[T]): (KubernetesClient[IO], Id[T], T) => IO[Status] =
    (_, _, _) => io.raiseError(new RuntimeException(
      s"resource ${ct.getClass.getSimpleName} does not have a status sub-resource. " +
      "(if it should, please update kubernetesclientoperator.implicits to expose it)")
    )

  implicit def implicitPodResource[IO[_]: Async]: ResourceImpl[IO, PodStatus, Pod, PodList] =
    new ResourceImpl[IO, PodStatus, Pod, PodList](
      _.pods,
      (o, m) => o.copy(metadata=Some(m)),
      (o, s) => o.copy(status=Some(s)),
      cantUpdateStatus[IO, Pod]
    )

  implicit def implicitCustomResourceResource[IO[_], Sp : Encoder: Decoder, St: Encoder: Decoder]
    (implicit crd: CrdContext[CustomResource[Sp, St]])
    : ResourceImpl[IO, St, CustomResource[Sp,St], CustomResourceList[Sp, St]]
    = new ResourceImpl[IO, St, CustomResource[Sp, St], CustomResourceList[Sp, St]](
      _.customResources[Sp, St](crd.ctx),
      (o, m) => o.copy(metadata=Some(m)),
      (o, s) => o.copy(status=Some(s)),
      (c: client.KubernetesClient[IO], id: Id[CustomResource[Sp, St]], t: CustomResource[Sp, St]) => {
        c.customResources[Sp,St](crd.ctx).namespace(id.namespace).updateStatus(id.name, t)
      }
    )

  // TODO implement in ResourceImpl
  implicit def implicitCustomResourceSpec[Sp,St]: HasSpec[CustomResource[Sp, St], Sp]
  = new HasSpec[CustomResource[Sp, St], Sp] {
    override def spec(obj: CustomResource[Sp, St]): Sp = obj.spec

    override def withSpec(obj: CustomResource[Sp, St], spec: Sp): CustomResource[Sp, St] = obj.copy(spec = spec)
  }

  // CRDs aren't namespaced, so some of this is nonsensical...
  implicit def implicitCrdResource[IO[_]: Async]: ResourceImpl[IO, CustomResourceDefinitionStatus, CustomResourceDefinition, CustomResourceDefinitionList]
  = new ResourceImpl[IO, CustomResourceDefinitionStatus, CustomResourceDefinition, CustomResourceDefinitionList](
    ???,
//    _.customResourceDefinitions,
    (o, m) => o.copy(metadata=Some(m)),
    (o, s) => o.copy(status=Some(s)),
    cantUpdateStatus[IO, CustomResourceDefinition]
  )

  // TODO remaining builtin types
}
