/*
 * Copyright 2019-2020, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */

package install

import (
	"context"
	"fmt"
	"k8s.io/apimachinery/pkg/api/meta"
	"reflect"

	"k8s.io/apimachinery/pkg/api/errors"

	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"

	"k8s.io/apimachinery/pkg/apis/meta/v1"

	"k8s.io/apimachinery/pkg/labels"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/selection"
	"sigs.k8s.io/controller-runtime/pkg/client"
)

type DeletePredicate func(interface{}) bool

type BulkItemOperation func(context.Context, client.Client, interface{}) error
type BulkItemEvaluator func(interface{}) (BulkItemOperation, error)

type OwnerResult int

const (
	NotFound OwnerResult = iota
	Found
	FoundAndEmpty
)

// Function to check if an object is owned by another object
func ProcessOwnedBy(obj v1.Object, owner runtime.Object, controller bool, remove bool) (OwnerResult, error) {

	ownerKind := owner.GetObjectKind()
	ownerKindName := ownerKind.GroupVersionKind().Kind
	ownerApiVersion := ownerKind.GroupVersionKind().GroupVersion().String()

	// get name

	oma, ok := owner.(v1.ObjectMetaAccessor)
	if !ok {
		return NotFound, fmt.Errorf("failed to get object metadata, wrong type: %T", owner)
	}
	meta := oma.GetObjectMeta()

	// get references

	refs := obj.GetOwnerReferences()

	for i, r := range refs {

		if controller {
			if r.Controller == nil || !*r.Controller {
				continue
			}
		}

		if r.Kind != ownerKindName {
			continue
		}

		if r.APIVersion != ownerApiVersion {
			continue
		}

		if r.Name != meta.GetName() {
			continue
		}

		if r.UID != meta.GetUID() {
			continue
		}

		if !remove {
			// we should only find it ... we did
			return Found, nil
		} else {
			// find and remove
			refs = append(refs[:i], refs[i+1:]...)
			obj.SetOwnerReferences(refs)
			if len(refs) == 0 {
				// this was the last owner
				return FoundAndEmpty, nil
			} else {
				// there are more owners
				return Found, nil
			}
		}

	}

	return NotFound, nil

}

func IsOwnedBy(owner runtime.Object, obj interface{}, controller bool) (bool, error) {
	r, err := ProcessOwnedByObject(owner, obj, controller, false)
	return r == Found, err
}

// same as, ProcessOwnedBy, but handles interfaces (structured and unstructured)
func ProcessOwnedByObject(owner runtime.Object, obj interface{}, controller bool, remove bool) (OwnerResult, error) {

	switch v := obj.(type) {
	case *unstructured.Unstructured:
		return ProcessOwnedBy(v, owner, controller, remove)
	case v1.ObjectMetaAccessor:
		return ProcessOwnedBy(v.GetObjectMeta(), owner, controller, remove)
	default:
		return NotFound, fmt.Errorf("provided unknown type: %T", v)
	}

}

// Delete object, ignore if it is already gone or the kind is unknown.
func DeleteIgnoreNotFound(ctx context.Context, client client.Client, obj runtime.Object, opts ...client.DeleteOption) error {

	err := client.Delete(ctx, obj, opts...)

	// check errors ...

	if err == nil {
		// ... all good
		return nil
	}

	if errors.IsNotFound(err) {
		// ... instance not found, no problem, we had to delete it anyway
		return nil
	}

	switch err.(type) {
	case *meta.NoKindMatchError:
		// ... kind not registered, no problem, we had to delete it anyway
		return nil
	}

	// everything else is an error we report

	return err

}

// Delete an object, if it is owned by the owner.
// Otherwise leave it alone. If the object doesn't exists, this will be reported as an error which can be checked by IsNotFound(err)
func DeleteIfOwnedBy(ctx context.Context, c client.Client, objectKey client.ObjectKey, obj runtime.Object, owner runtime.Object, controller bool) (OwnerResult, error) {

	if err := c.Get(ctx, objectKey, obj); err != nil {
		return 0, err
	}

	result, err := RemoveAsOwner(owner, obj, controller)
	if err != nil {
		return 0, err
	}

	switch result {
	case Found: // found and removed
		return result, c.Update(ctx, obj)
	case FoundAndEmpty: // found, removed and no more owners
		return result, c.Delete(ctx, obj)
	default:
		return result, nil
	}

}

func UpdateItemOperation(ctx context.Context, client client.Client, obj interface{}) error {

	switch o := obj.(type) {
	case runtime.Object:
		return client.Update(ctx, o)
	case *unstructured.Unstructured:
		return client.Update(ctx, o)
	default:
		return fmt.Errorf("type %T is not supported", o)
	}

}

func DeleteItemOperation(ctx context.Context, client client.Client, obj interface{}) error {

	switch o := obj.(type) {
	case runtime.Object:
		return client.Delete(ctx, o)
	case *unstructured.Unstructured:
		return client.Delete(ctx, o)
	default:
		return fmt.Errorf("type %T is not supported", o)
	}

}

// Bulk delete
// The "obj" provided must by a Kubernetes List type, having an "Items" field
func BulkProcess(ctx context.Context, client client.Client, list runtime.Object, opts client.ListOptions, evaluator BulkItemEvaluator) (int, error) {

	if err := client.List(ctx, list, &opts); err != nil {
		log.Error(err, "Failed to list items to delete")
		return -1, err
	}

	val := reflect.ValueOf(list).Elem()
	items := val.FieldByName("Items")

	if items.Kind() != reflect.Slice {
		return -1, fmt.Errorf("object does not have an array named 'Items'")
	}

	l := items.Len()
	n := 0
	for i := 0; i < l; i++ {
		item := items.Index(i)

		obj := item.Addr().Interface()

		op, err := evaluator(obj)
		if err != nil {
			return -1, err
		}

		if op == nil {
			continue
		}

		n++

		if err := op(ctx, client, obj); err != nil {
			return -1, err
		}
	}

	return n, nil
}

func LabelSelectorFromMap(l map[string]string) (labels.Selector, error) {
	ls := labels.NewSelector()

	for k, v := range l {
		r, err := labels.NewRequirement(k, selection.Equals, []string{v})
		if err != nil {
			return nil, err
		}
		ls = ls.Add(*r)
	}

	return ls, nil
}

// Process an object type, and remove the owner, from the owner list. If this was the last owner. Delete the object.
// The "obj" provided must by a Kubernetes List type, having an "Items" field
// It returns the number of entries found which did contain the owner of the provided object.
func BulkRemoveOwner(ctx context.Context, c client.Client, owner runtime.Object, controller bool, list runtime.Object, opts client.ListOptions) (int, error) {

	return BulkProcess(ctx, c, list, opts, func(i interface{}) (BulkItemOperation, error) {

		// process ownership

		result, err := RemoveAsOwner(owner, i, controller)
		if err != nil {
			return nil, err
		}

		// check result

		switch result {
		case Found:
			// owner remaining
			return UpdateItemOperation, nil
		case FoundAndEmpty:
			// delete object
			return DeleteItemOperation, nil
		}

		// do nothing

		return nil, nil

	})

}

func RemoveAsOwner(owner runtime.Object, object interface{}, controller bool) (OwnerResult, error) {
	return ProcessOwnedByObject(owner, object, controller, true)
}

// Test if an object is being deleted
func IsDeleted(obj interface{}) (bool, error) {
	switch v := obj.(type) {
	case *unstructured.Unstructured:
		return v.GetDeletionTimestamp() != nil, nil
	case v1.ObjectMetaAccessor:
		return v.GetObjectMeta().GetDeletionTimestamp() != nil, nil
	default:
		return false, fmt.Errorf("provided unknown type: %T", v)
	}
}
