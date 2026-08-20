package main

import (
	"fmt"
	"os"

	"familyorbit/server/internal/app"
)

func main() {
	mode := "api"
	if len(os.Args) > 1 {
		mode = os.Args[1]
	}
	if mode != "api" && mode != "worker" {
		fmt.Fprintln(os.Stderr, "usage: family-orbit-api [api|worker]")
		os.Exit(2)
	}
	if err := app.Run(mode); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
