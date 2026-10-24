//go:build android

/*
Copyright (C) 2026  dyhkwong

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package libsagernetcore

import (
	// Binding a local module (`gomobile bind .`) always contains local path
	// and is not reproducible. See https://github.com/golang/go/issues/40254
	// and https://github.com/golang/go/issues/73097. As a workaround, bind
	// a remote module (`gomobile bind "example.com/module"`) instead.
	_ "github.com/dyhkwong/libsagernetcore"
)
