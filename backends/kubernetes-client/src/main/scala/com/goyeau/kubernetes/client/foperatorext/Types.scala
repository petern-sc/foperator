package com.goyeau.kubernetes.client.foperatorext

import com.goyeau.kubernetes.client.operation._
import io.k8s.apimachinery.pkg.apis.meta.v1.{ListMeta, ObjectMeta}
import org.http4s.Uri

// Kubernetes-client doesn't have any shared hierarchy, so we use structural types.
// We also cheekily put this in their namespace so we can use package-private traits.
// This surely breaks semtantic versioning, hopefully the traits become public in the future.
object Types {
  type HasMetadata = {
    def metadata: Option[ObjectMeta]
  }

  type ResourceGetters[St] = {
    def metadata: Option[ObjectMeta]
    def status: Option[St]
  }

  type ListOf[T] = {
    def metadata: Option[ListMeta]
    def items: Seq[T]
  }

  type HasResourceURI = {
    def resourceUri: Uri
  }

  // nested API, e.g. NamespacedPodsApi
  type NamespacedResourceAPI[IO[_], T<:HasMetadata, TList<:ListOf[T]] =
    Creatable[IO, T]
    with Replaceable[IO, T]
    with Gettable[IO, T]
    with Listable[IO, TList]
    with Deletable[IO]
    with Watchable[IO, T]
}