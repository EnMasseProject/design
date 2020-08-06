/*
 * Copyright 2018-2019, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */

// Code generated by client-gen. DO NOT EDIT.

package v1

import (
	"time"

	v1 "github.com/enmasseproject/enmasse/pkg/apis/iot/v1"
	scheme "github.com/enmasseproject/enmasse/pkg/client/clientset/versioned/scheme"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	types "k8s.io/apimachinery/pkg/types"
	watch "k8s.io/apimachinery/pkg/watch"
	rest "k8s.io/client-go/rest"
)

// IoTInfrastructuresGetter has a method to return a IoTInfrastructureInterface.
// A group's client should implement this interface.
type IoTInfrastructuresGetter interface {
	IoTInfrastructures(namespace string) IoTInfrastructureInterface
}

// IoTInfrastructureInterface has methods to work with IoTInfrastructure resources.
type IoTInfrastructureInterface interface {
	Create(*v1.IoTInfrastructure) (*v1.IoTInfrastructure, error)
	Update(*v1.IoTInfrastructure) (*v1.IoTInfrastructure, error)
	UpdateStatus(*v1.IoTInfrastructure) (*v1.IoTInfrastructure, error)
	Delete(name string, options *metav1.DeleteOptions) error
	DeleteCollection(options *metav1.DeleteOptions, listOptions metav1.ListOptions) error
	Get(name string, options metav1.GetOptions) (*v1.IoTInfrastructure, error)
	List(opts metav1.ListOptions) (*v1.IoTInfrastructureList, error)
	Watch(opts metav1.ListOptions) (watch.Interface, error)
	Patch(name string, pt types.PatchType, data []byte, subresources ...string) (result *v1.IoTInfrastructure, err error)
	IoTInfrastructureExpansion
}

// ioTInfrastructures implements IoTInfrastructureInterface
type ioTInfrastructures struct {
	client rest.Interface
	ns     string
}

// newIoTInfrastructures returns a IoTInfrastructures
func newIoTInfrastructures(c *IotV1Client, namespace string) *ioTInfrastructures {
	return &ioTInfrastructures{
		client: c.RESTClient(),
		ns:     namespace,
	}
}

// Get takes name of the ioTInfrastructure, and returns the corresponding ioTInfrastructure object, and an error if there is any.
func (c *ioTInfrastructures) Get(name string, options metav1.GetOptions) (result *v1.IoTInfrastructure, err error) {
	result = &v1.IoTInfrastructure{}
	err = c.client.Get().
		Namespace(c.ns).
		Resource("iotinfrastructures").
		Name(name).
		VersionedParams(&options, scheme.ParameterCodec).
		Do().
		Into(result)
	return
}

// List takes label and field selectors, and returns the list of IoTInfrastructures that match those selectors.
func (c *ioTInfrastructures) List(opts metav1.ListOptions) (result *v1.IoTInfrastructureList, err error) {
	var timeout time.Duration
	if opts.TimeoutSeconds != nil {
		timeout = time.Duration(*opts.TimeoutSeconds) * time.Second
	}
	result = &v1.IoTInfrastructureList{}
	err = c.client.Get().
		Namespace(c.ns).
		Resource("iotinfrastructures").
		VersionedParams(&opts, scheme.ParameterCodec).
		Timeout(timeout).
		Do().
		Into(result)
	return
}

// Watch returns a watch.Interface that watches the requested ioTInfrastructures.
func (c *ioTInfrastructures) Watch(opts metav1.ListOptions) (watch.Interface, error) {
	var timeout time.Duration
	if opts.TimeoutSeconds != nil {
		timeout = time.Duration(*opts.TimeoutSeconds) * time.Second
	}
	opts.Watch = true
	return c.client.Get().
		Namespace(c.ns).
		Resource("iotinfrastructures").
		VersionedParams(&opts, scheme.ParameterCodec).
		Timeout(timeout).
		Watch()
}

// Create takes the representation of a ioTInfrastructure and creates it.  Returns the server's representation of the ioTInfrastructure, and an error, if there is any.
func (c *ioTInfrastructures) Create(ioTInfrastructure *v1.IoTInfrastructure) (result *v1.IoTInfrastructure, err error) {
	result = &v1.IoTInfrastructure{}
	err = c.client.Post().
		Namespace(c.ns).
		Resource("iotinfrastructures").
		Body(ioTInfrastructure).
		Do().
		Into(result)
	return
}

// Update takes the representation of a ioTInfrastructure and updates it. Returns the server's representation of the ioTInfrastructure, and an error, if there is any.
func (c *ioTInfrastructures) Update(ioTInfrastructure *v1.IoTInfrastructure) (result *v1.IoTInfrastructure, err error) {
	result = &v1.IoTInfrastructure{}
	err = c.client.Put().
		Namespace(c.ns).
		Resource("iotinfrastructures").
		Name(ioTInfrastructure.Name).
		Body(ioTInfrastructure).
		Do().
		Into(result)
	return
}

// UpdateStatus was generated because the type contains a Status member.
// Add a +genclient:noStatus comment above the type to avoid generating UpdateStatus().

func (c *ioTInfrastructures) UpdateStatus(ioTInfrastructure *v1.IoTInfrastructure) (result *v1.IoTInfrastructure, err error) {
	result = &v1.IoTInfrastructure{}
	err = c.client.Put().
		Namespace(c.ns).
		Resource("iotinfrastructures").
		Name(ioTInfrastructure.Name).
		SubResource("status").
		Body(ioTInfrastructure).
		Do().
		Into(result)
	return
}

// Delete takes name of the ioTInfrastructure and deletes it. Returns an error if one occurs.
func (c *ioTInfrastructures) Delete(name string, options *metav1.DeleteOptions) error {
	return c.client.Delete().
		Namespace(c.ns).
		Resource("iotinfrastructures").
		Name(name).
		Body(options).
		Do().
		Error()
}

// DeleteCollection deletes a collection of objects.
func (c *ioTInfrastructures) DeleteCollection(options *metav1.DeleteOptions, listOptions metav1.ListOptions) error {
	var timeout time.Duration
	if listOptions.TimeoutSeconds != nil {
		timeout = time.Duration(*listOptions.TimeoutSeconds) * time.Second
	}
	return c.client.Delete().
		Namespace(c.ns).
		Resource("iotinfrastructures").
		VersionedParams(&listOptions, scheme.ParameterCodec).
		Timeout(timeout).
		Body(options).
		Do().
		Error()
}

// Patch applies the patch and returns the patched ioTInfrastructure.
func (c *ioTInfrastructures) Patch(name string, pt types.PatchType, data []byte, subresources ...string) (result *v1.IoTInfrastructure, err error) {
	result = &v1.IoTInfrastructure{}
	err = c.client.Patch(pt).
		Namespace(c.ns).
		Resource("iotinfrastructures").
		SubResource(subresources...).
		Name(name).
		Body(data).
		Do().
		Into(result)
	return
}