/*
 * Copyright 2020, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 *
 */

package accesscontroller

import (
	"testing"

	v1 "github.com/enmasseproject/enmasse/pkg/apis/enmasse/v1"
	"github.com/enmasseproject/enmasse/pkg/consolegraphql"
	"github.com/stretchr/testify/assert"
	authv1 "k8s.io/api/authorization/v1"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/client-go/kubernetes"
	"k8s.io/client-go/kubernetes/fake"
	clientTesting "k8s.io/client-go/testing"
)

type acTestResource struct {
	clientset kubernetes.Interface
}

func newAcTestResource(*testing.T) *acTestResource {
	return &acTestResource{
		clientset: &fake.Clientset{},
	}
}

func TestReadAddressAllowed(t *testing.T) {
	tr := newAcTestResource(t)

	accessController := NewKubernetesRBACAccessController(tr.clientset, nil)
	address := createAddress("foons", "foo")

	configureReactor(tr.clientset, ssarResponseYielding(true))
	read, err := accessController.CanRead(address)
	assert.NoError(t, err)
	assert.True(t, read)
}

func TestAllowedAccessRequestsCached(t *testing.T) {
	tr := newAcTestResource(t)

	accessController := NewKubernetesRBACAccessController(tr.clientset, nil)
	address1 := createAddress("foons", "foo1")
	address2 := createAddress("foons", "foo2")

	ssars := 0
	configureReactor(tr.clientset, ssarResponseYielding(true, func() { ssars++ }))

	read, err := accessController.CanRead(address1)
	assert.NoError(t, err)
	assert.True(t, read)
	assert.Equal(t, 1, ssars)

	read, err = accessController.CanRead(address2)
	assert.NoError(t, err)
	assert.True(t, read)
	assert.Equal(t, 1, ssars)

	read, err = accessController.CanRead(address1)
	assert.NoError(t, err)
	assert.True(t, read)
	assert.Equal(t, 1, ssars)

}

func TestReadAddressesDifferentNamespacePermissions(t *testing.T) {
	tr := newAcTestResource(t)

	accessController := NewKubernetesRBACAccessController(tr.clientset, nil)
	addr1 := createAddress("secretns", "myaddr")
	addr2 := createAddress("publicns", "myaddr")

	configureReactor(tr.clientset, ssarResponseDenyMatching(
		&authv1.ResourceAttributes{
			Namespace: addr1.Namespace,
			Group:     "enmasse.io",
			Version:   "v1beta1",
			Resource:  "addresses",
		}))

	addr1Readable, err := accessController.CanRead(addr1)
	assert.NoError(t, err)
	assert.False(t, addr1Readable)

	addr2Readable, err := accessController.CanRead(addr2)
	assert.NoError(t, err)
	assert.True(t, addr2Readable)

}

func TestAuthorizationState(t *testing.T) {
	tr := newAcTestResource(t)

	accessController := NewKubernetesRBACAccessController(tr.clientset, nil)
	address1 := createAddress("foons", "foo1")

	ssars := 0
	configureReactor(tr.clientset, ssarResponseYielding(true, func() { ssars++ }))

	read, err := accessController.CanRead(address1)
	assert.NoError(t, err)
	assert.True(t, read)
	assert.Equal(t, 1, ssars)

	stateUpdated, state := accessController.GetState()
	assert.True(t, stateUpdated)
	accessController = NewKubernetesRBACAccessController(tr.clientset, state)

	read, err = accessController.CanRead(address1)
	assert.NoError(t, err)
	assert.True(t, read)
	assert.Equal(t, 1, ssars)
}

func TestReadNamespaceAllowed(t *testing.T) {
	tr := newAcTestResource(t)

	accessController := NewKubernetesRBACAccessController(tr.clientset, nil)
	namespace := createNamespace("foons")

	configureReactor(tr.clientset, ssarResponseYielding(true))
	read, err := accessController.CanRead(namespace)
	assert.NoError(t, err)
	assert.True(t, read)
}

func TestReadNamespaceDenied(t *testing.T) {
	tr := newAcTestResource(t)

	accessController := NewKubernetesRBACAccessController(tr.clientset, nil)
	namespace := createNamespace("foons")

	configureReactor(tr.clientset, ssarResponseDenyMatching(
		&authv1.ResourceAttributes{
			Namespace: namespace.Name,
			Name:      namespace.Name,
			Version:   "v1",
			Resource:  "namespaces",
		}))

	read, err := accessController.CanRead(namespace)
	assert.NoError(t, err)
	assert.False(t, read)
}

func createAddress(namespace, name string) *consolegraphql.AddressHolder {
	address := &consolegraphql.AddressHolder{
		MessagingAddress: v1.MessagingAddress{
			TypeMeta: metav1.TypeMeta{
				APIVersion: "enmasse.io/v1",
				Kind:       "MessagingAddress",
			},
			ObjectMeta: metav1.ObjectMeta{
				Namespace: namespace,
				Name:      name,
			},
		},
	}
	return address
}

func createNamespace(name string) *corev1.Namespace {
	namespace := &corev1.Namespace{
		TypeMeta: metav1.TypeMeta{
			APIVersion: "v1",
			Kind:       "Namespace",
		},
		ObjectMeta: metav1.ObjectMeta{
			Name: name,
		},
	}
	return namespace
}

func configureReactor(clientset kubernetes.Interface, function func(action clientTesting.Action) (handled bool, ret runtime.Object, err error)) {
	clientset.(*fake.Clientset).Fake.AddReactor("create", "selfsubjectaccessreviews", function)
}

func ssarResponseDenyMatching(denied ...*authv1.ResourceAttributes) func(action clientTesting.Action) (handled bool, ret runtime.Object, err error) {
	return func(action clientTesting.Action) (handled bool, ret runtime.Object, err error) {
		// NB 'create' here refers to the sending of the SSAR itself rather than its object
		create := action.(clientTesting.CreateAction)
		review := create.GetObject().(*authv1.SelfSubjectAccessReview).Spec.ResourceAttributes

		allowed := true
		for _, deny := range denied {
			if deny.Namespace == review.Namespace &&
				deny.Name == review.Name &&
				deny.Version == review.Version &&
				deny.Resource == review.Resource &&
				deny.Group == review.Group {
				allowed = false
				break
			}
		}

		res := &authv1.SelfSubjectAccessReview{
			Status: authv1.SubjectAccessReviewStatus{
				Allowed: allowed,
			},
		}

		return true, res, nil
	}
}

func ssarResponseYielding(result bool, cbs ...func()) func(action clientTesting.Action) (handled bool, ret runtime.Object, err error) {
	return func(action clientTesting.Action) (handled bool, ret runtime.Object, err error) {

		for _, cb := range cbs {
			cb()
		}

		res := &authv1.SelfSubjectAccessReview{
			Status: authv1.SubjectAccessReviewStatus{
				Allowed: result,
			},
		}

		return true, res, nil
	}
}
