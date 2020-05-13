/*
 * Copyright 2019, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 *
 */

package resolvers

import (
	"context"
	"github.com/99designs/gqlgen/graphql"
	"github.com/enmasseproject/enmasse/pkg/consolegraphql"
	"github.com/enmasseproject/enmasse/pkg/consolegraphql/cache"
	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/types"
	"testing"
)

func newTestLinkResolver(t *testing.T) *Resolver {
	objectCache, err := cache.CreateObjectCache()
	assert.NoError(t, err)

	resolver := Resolver{}
	resolver.Cache = objectCache
	return &resolver
}

func TestLinkConnection(t *testing.T) {
	r := newTestLinkResolver(t)

	uid := uuid.New().String()
	namespace := "mynamespace"
	addressspace := "myaddressspace"
	addr := &consolegraphql.Connection{
		TypeMeta: metav1.TypeMeta{
			Kind: "Connection",
		},
		ObjectMeta: metav1.ObjectMeta{
			Name:      uid,
			UID:       types.UID(uid),
			Namespace: namespace,
		},
		Spec: consolegraphql.ConnectionSpec{
			AddressSpace: addressspace,
		},
	}
	err := r.Cache.Add(addr)
	assert.NoError(t, err)

	obj := &consolegraphql.LinkSpec{
		Connection:   uid,
		AddressSpace: addressspace,
	}

	ctx := buildResolverContext(namespace)
	con, err := r.LinkSpec_consoleapi_enmasse_io_v1beta1().Connection(ctx, obj)
	assert.NoError(t, err)

	assert.Equal(t, uid, con.ObjectMeta.Name, "unexpected connection uid")
}

func buildResolverContext(namespace string) context.Context {
	link := &consolegraphql.Link{
		ObjectMeta: metav1.ObjectMeta{
			Namespace: namespace,
		},
	}
	ctx := graphql.WithFieldContext(context.TODO(), &graphql.FieldContext{
		Result: &link,
	})
	ctx = graphql.WithFieldContext(ctx, &graphql.FieldContext{})
	ctx = graphql.WithFieldContext(ctx, &graphql.FieldContext{})
	return ctx
}
