/*
 * Copyright 2019, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */

package resolvers

import (
	"context"
	"fmt"
	"github.com/99designs/gqlgen/graphql"
	"github.com/enmasseproject/enmasse/pkg/apis/admin/v1beta2"
	"github.com/enmasseproject/enmasse/pkg/apis/enmasse/v1beta1"
	"github.com/enmasseproject/enmasse/pkg/consolegraphql"
	"github.com/enmasseproject/enmasse/pkg/consolegraphql/cache"
	"github.com/enmasseproject/enmasse/pkg/consolegraphql/server"
	"github.com/google/uuid"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/types"
	"regexp"
	"strings"
)

// From https://kubernetes.io/docs/concepts/overview/working-with-objects/names/#names
var validDnsSubDomainRfc1123NameRegexp = regexp.MustCompile("^[a-z0-9]([-a-z0-9]*[a-z0-9])?(\\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*$")
var legalNameCharRegexp = regexp.MustCompile("[^-a-z0-9_.]")
var separators = []string{"_", ".", "-"}

const maxKubeName = 253

func (r *Resolver) Address_consoleapi_enmasse_io_v1beta1() Address_consoleapi_enmasse_io_v1beta1Resolver {
	return &addressK8sResolver{r}
}

type addressK8sResolver struct{ *Resolver }

const infraUuidAnnotation = "enmasse.io/infra-uuid"

func (ar addressK8sResolver) Links(ctx context.Context, obj *consolegraphql.AddressHolder, first *int, offset *int, filter *string, orderBy *string) (*LinkQueryResultConsoleapiEnmasseIoV1beta1, error) {
	if obj != nil {
		fltrfunc, keyElements, e := BuildFilter(filter, "$.spec.address")
		if e != nil {
			return nil, e
		}

		orderer, e := BuildOrderer(orderBy)
		if e != nil {
			return nil, e
		}

		addrtoks, e := tokenizeAddress(obj.ObjectMeta.Name)
		if e != nil {
			return nil, e
		}
		// N.B. address name not prefixed in the link index
		links, e := ar.Cache.Get(cache.AddressLinkObjectIndex, fmt.Sprintf("Link/%s/%s/%s/%s", obj.ObjectMeta.Namespace, addrtoks[0], obj.Spec.Address, keyElements), fltrfunc)
		if e != nil {
			return nil, e
		}

		e = orderer(links)
		if e != nil {
			return nil, e
		}

		lower, upper := CalcLowerUpper(offset, first, len(links))
		paged := links[lower:upper]

		consolelinks := make([]*consolegraphql.Link, 0)
		for _, obj := range paged {
			link := obj.(*consolegraphql.Link)
			consolelinks = append(consolelinks, &consolegraphql.Link{
				ObjectMeta: link.ObjectMeta,
				Spec:       link.Spec,
				Metrics:    link.Metrics,
			})
		}

		return &LinkQueryResultConsoleapiEnmasseIoV1beta1{
			Total: len(links),
			Links: consolelinks,
		}, nil
	}
	return nil, nil
}

func (r *Resolver) AddressSpec_enmasse_io_v1beta1() AddressSpec_enmasse_io_v1beta1Resolver {
	return &addressSpecK8sResolver{r}
}

type addressSpecK8sResolver struct{ *Resolver }

func (r *queryResolver) Addresses(ctx context.Context, first *int, offset *int, filter *string, orderBy *string) (*AddressQueryResultConsoleapiEnmasseIoV1beta1, error) {
	requestState := server.GetRequestStateFromContext(ctx)
	viewFilter := requestState.AccessController.ViewFilter()

	fltrfunc, keyElements, e := BuildFilter(filter, "$.metadata.namespace", "$.metadata.name")
	if e != nil {
		return nil, e
	}

	orderer, e := BuildOrderer(orderBy)
	if e != nil {
		return nil, e
	}

	objects, e := r.Cache.Get(cache.PrimaryObjectIndex, fmt.Sprintf("Address/%s", keyElements), cache.And(viewFilter, fltrfunc))
	if e != nil {
		return nil, e
	}

	e = orderer(objects)
	if e != nil {
		return nil, e
	}

	lower, upper := CalcLowerUpper(offset, first, len(objects))
	paged := objects[lower:upper]

	addresses := make([]*consolegraphql.AddressHolder, len(paged))
	for i, _ := range paged {
		addresses[i] = paged[i].(*consolegraphql.AddressHolder)
	}

	aqr := &AddressQueryResultConsoleapiEnmasseIoV1beta1{
		Total:     len(objects),
		Addresses: addresses,
	}

	return aqr, nil
}

func (r *addressSpecK8sResolver) Plan(ctx context.Context, obj *v1beta1.AddressSpec) (*v1beta2.AddressPlan, error) {
	if obj != nil {
		addressPlanName := obj.Plan
		planFilter := func(obj interface{}) (bool, bool, error) {
			asp, ok := obj.(*v1beta2.AddressPlan)
			if !ok {
				return false, false, fmt.Errorf("unexpected type: %T", obj)
			}
			return asp.Name == addressPlanName, true, nil
		}

		objs, e := r.Cache.Get(cache.PrimaryObjectIndex, "AddressPlan", planFilter)
		if e != nil {
			return nil, e
		}

		if len(objs) == 0 {
			// There might be a plan change in progress, or the user may have created an address referring to
			// an unknown plan.
			return &v1beta2.AddressPlan{
				ObjectMeta: metav1.ObjectMeta{
					Name: addressPlanName,
				},
				Spec: v1beta2.AddressPlanSpec{
					DisplayName: addressPlanName,
				},
			}, nil
		}

		ap := objs[0].(*v1beta2.AddressPlan)
		return ap, nil
	}
	return nil, nil
}

func (r *addressSpecK8sResolver) Type(ctx context.Context, obj *v1beta1.AddressSpec) (AddressType, error) {
	return AddressType(obj.Type), nil
}

func (r *mutationResolver) CreateAddress(ctx context.Context, input v1beta1.Address, addressSpace *string) (*metav1.ObjectMeta, error) {
	requestState := server.GetRequestStateFromContext(ctx)

	if input.ObjectMeta.Name == "" {
		err := defaultResourceNameFromAddress(&input, addressSpace)
		if err != nil {
			return nil, err
		}
	}

	nw, e := requestState.EnmasseV1beta1Client.Addresses(input.Namespace).Create(&input)
	if e != nil {
		return nil, e
	}
	return &nw.ObjectMeta, e
}

func (r *mutationResolver) PatchAddress(ctx context.Context, input metav1.ObjectMeta, patch string, patchType string) (*bool, error) {
	pt := types.PatchType(patchType)
	requestState := server.GetRequestStateFromContext(ctx)

	_, e := requestState.EnmasseV1beta1Client.Addresses(input.Namespace).Patch(input.Name, pt, []byte(patch))
	b := e == nil
	return &b, e
}

func (r *mutationResolver) DeleteAddress(ctx context.Context, input metav1.ObjectMeta) (*bool, error) {
	return r.DeleteAddresses(ctx, []*metav1.ObjectMeta{&input})
}

func (r *mutationResolver) DeleteAddresses(ctx context.Context, input []*metav1.ObjectMeta) (*bool, error) {
	requestState := server.GetRequestStateFromContext(ctx)
	t := true

	for _, a := range input {
		e := requestState.EnmasseV1beta1Client.Addresses(a.Namespace).Delete(a.Name, &metav1.DeleteOptions{})
		if e != nil {
			graphql.AddErrorf(ctx, "failed to delete address: '%s' in namespace: '%s', %+v", a.Name, a.Namespace, e)
		}
	}
	return &t, nil
}

func (r *mutationResolver) PurgeAddress(ctx context.Context, input metav1.ObjectMeta) (*bool, error) {
	return r.PurgeAddresses(ctx, []*metav1.ObjectMeta{&input})
}

func (r *mutationResolver) PurgeAddresses(ctx context.Context, inputs []*metav1.ObjectMeta) (*bool, error) {
	requestState := server.GetRequestStateFromContext(ctx)

	t := true

	for _, input := range inputs {
		addressToks, e := tokenizeAddress(input.Name)
		if e != nil {
			return &t, e
		}

		infraUid, e := r.GetInfraUid(input.Namespace, addressToks[0])
		if e != nil {
			graphql.AddErrorf(ctx, "failed to purge address: '%s' in namespace: '%s' - %+v", input.Name, input.Namespace, e)
			continue
		}

		addresses, e := r.Cache.Get(cache.PrimaryObjectIndex, fmt.Sprintf("Address/%s/%s", input.Namespace, input.Name), nil)
		if e != nil {
			graphql.AddErrorf(ctx, "failed to purge address: '%s' in namespace: '%s' - %+v", input.Name, input.Namespace, e)
			continue
		}

		if len(addresses) == 0 {
			graphql.AddErrorf(ctx, "failed to purge address: '%s' in namespace: '%s' - address not found.", input.Name, input.Namespace)
			continue
		}

		address := addresses[0].(*consolegraphql.AddressHolder).Address
		switch address.Spec.Type {
		case "subscription":
		case "queue":
		default:
			graphql.AddErrorf(ctx, "failed to purge address: '%s' in namespace: '%s' - address type '%s' is not supported for this operation", input.Name, input.Namespace, address.Spec.Type)
			continue
		}

		collector := r.GetCollector(infraUid)
		if collector == nil {
			graphql.AddErrorf(ctx, "failed to purge address: '%s' in namespace: '%s' - cannot find collector for infraUuid '%s' at this time",
				input.Name, input.Namespace, infraUid)
			continue
		}
		token := requestState.UserAccessToken

		commandDelegate, e := collector.CommandDelegate(token, requestState.ImpersonatedUser)
		if e != nil {
			graphql.AddErrorf(ctx, "failed to purge address: '%s' in namespace '%s', %+v", input.Name, input.Namespace, e)
			continue
		}

		e = commandDelegate.PurgeAddress(address.Spec.Address)
		if e != nil {
			graphql.AddErrorf(ctx, "failed to purge address: '%s' in namespace '%s', %+v", input.Name, input.Namespace, e)
		}
	}
	return &t, nil
}

func (r *mutationResolver) GetInfraUid(namespace, addressSpaceName string) (string, error) {

	addressSpaces, e := r.Cache.Get(cache.PrimaryObjectIndex, fmt.Sprintf("AddressSpace/%s/%s", namespace, addressSpaceName), nil)
	if e != nil {
		return "", e
	}

	if len(addressSpaces) == 0 {
		return "", fmt.Errorf("address space '%s' not found in namespace '%s'", addressSpaceName, namespace)
	}

	as := addressSpaces[0].(*consolegraphql.AddressSpaceHolder).AddressSpace

	if as.ObjectMeta.Annotations == nil || as.ObjectMeta.Annotations[infraUuidAnnotation] == "" {
		return "", fmt.Errorf("address space '%s' in namespace '%s' does not have expected '%s' annotation", addressSpaceName, namespace, infraUuidAnnotation)
	}
	return as.ObjectMeta.Annotations[infraUuidAnnotation], nil

}
func (r *queryResolver) AddressCommand(ctx context.Context, input v1beta1.Address, addressSpace *string) (string, error) {

	if input.TypeMeta.APIVersion == "" {
		input.TypeMeta.APIVersion = "enmasse.io/v1beta1"
	}
	if input.TypeMeta.Kind == "" {
		input.TypeMeta.Kind = "Address"
	}

	namespace := input.Namespace
	input.Namespace = ""

	if input.ObjectMeta.Name == "" {
		err := defaultResourceNameFromAddress(&input, addressSpace)
		if err != nil {
			return "", err
		}
	}

	return generateApplyCommand(input, namespace)
}

func tokenizeAddress(name string) ([]string, error) {
	addressToks := strings.SplitN(name, ".", 2)
	if len(addressToks) != 2 {
		return []string{}, fmt.Errorf("unexpectedly formatted address: '%s'.  expected separator not found ", name)
	}
	return addressToks, nil
}

func defaultResourceNameFromAddress(input *v1beta1.Address, addressSpace *string) error {
	if input.Spec.Address == "" {
		return fmt.Errorf("address is undefined, cannot default resource name")
	}
	if addressSpace == nil {
		return fmt.Errorf("addressSpace is not provided, cannot default resource name from address '%s'",
			input.Spec.Address)
	}
	addr := strings.ToLower(input.Spec.Address)
	if !isValidName(addr, maxKubeName-len(*addressSpace)-1) {
		qualifier := uuid.New().String()
		addr = cleanName(addr, qualifier, maxKubeName-len(*addressSpace)-len(qualifier)-2)
	}
	input.ObjectMeta.Name = fmt.Sprintf("%s.%s", *addressSpace, addr)
	return nil
}

func isValidName(name string, maxLength int) bool {
	return validDnsSubDomainRfc1123NameRegexp.MatchString(name) && maxLength >= len(name)
}

func cleanName(name string, qualifier string, maxLength int) string {
	name = legalNameCharRegexp.ReplaceAllString(name, "")

	for _, s := range separators {
		name = strings.TrimPrefix(name, s)
		name = strings.TrimSuffix(name, s)
	}

	if len(name) > maxLength {
		name = name[:maxLength]
	}

	if !validDnsSubDomainRfc1123NameRegexp.MatchString(name) {
		name = ""
	}

	if name == "" {
		return qualifier
	} else {
		return name + "." + qualifier
	}
}
